package co.edu.upb.dfsha.client.sdk;

import co.edu.upb.dfsha.client.data.ByteRange;
import co.edu.upb.dfsha.client.integrity.FileHash;
import co.edu.upb.dfsha.client.metadata.BlockInfo;
import co.edu.upb.dfsha.client.metadata.CommitRequest;
import co.edu.upb.dfsha.client.metadata.CommittedBlock;
import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.LockGateway;
import co.edu.upb.dfsha.client.metadata.LockRequest;
import co.edu.upb.dfsha.client.metadata.OpenResult;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import co.edu.upb.dfsha.client.sdk.model.BlockRange;
import co.edu.upb.dfsha.client.sdk.model.LockHandle;
import co.edu.upb.dfsha.client.sdk.model.LockMode;
import co.edu.upb.dfsha.client.sdk.model.OpenMode;
import co.edu.upb.dfsha.client.sdk.model.ReplicaRef;
import co.edu.upb.dfsha.client.session.LeaseKeeper;
import co.edu.upb.dfsha.client.session.LocationCache;
import co.edu.upb.dfsha.client.transfer.BlockBuffer;
import co.edu.upb.dfsha.client.transfer.BlockFetcher;
import co.edu.upb.dfsha.client.transfer.DirtyBlockUploader;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/** Implementación de {@link DfsFile}: copy-on-write sobre un buffer local y publicación por versiones. */
final class RemoteFile implements DfsFile, LeaseKeeper.HandleListener {

    /** Colaboradores compartidos entre archivos abiertos. */
    record Services(FileSessionGateway files, LockGateway locks, DirtyBlockUploader uploader, BlockFetcher fetcher,
                    LeaseKeeper keeper, LocationCache locations) {
    }

    private final String path;
    private final OpenMode mode;
    private final Services services;
    private final BlockBuffer buffer;
    private final String handle;
    private final long blockSize;
    private final String leaseFencingToken;
    private final BlockLocator locator;
    private final NavigableSet<Integer> dirty = new TreeSet<>();
    private final List<LockHandle> ownLocks = new ArrayList<>();
    private long size;
    private volatile String readToken;
    private volatile DfsException lost;
    private boolean truncatePending;
    private boolean closed;

    RemoteFile(String path, OpenMode mode, OpenResult opened, BlockBuffer buffer, Services services) {
        this.path = path;
        this.mode = mode;
        this.services = services;
        this.buffer = buffer;
        this.handle = opened.handle();
        this.blockSize = opened.blockSize();
        this.leaseFencingToken = opened.lease() == null ? null : opened.lease().fencingToken();
        this.size = opened.size();
        this.readToken = opened.readToken();
        this.truncatePending = mode == OpenMode.WRITE;
        this.locator = new BlockLocator(path, opened, services.files(), services.locations(),
                token -> readToken = token);
        services.keeper().registrarHandle(handle, this);
    }

    @Override
    public synchronized int leer(long offset, ByteBuffer destination) {
        asegurarActivo();
        if (offset >= size) {
            return -1;
        }
        int total = 0;
        long position = offset;
        while (destination.hasRemaining() && position < size) {
            int idx = (int) (position / blockSize);
            long within = position % blockSize;
            int count = (int) Math.min(destination.remaining(), Math.min(blockSize - within, size - position));
            ByteBuffer part = destination.slice(destination.position(), count);
            leerParte(idx, within, count, part);
            destination.position(destination.position() + count);
            position += count;
            total += count;
        }
        return total;
    }

    private void leerParte(int idx, long within, int count, ByteBuffer part) {
        if (buffer.contiene(idx)) {
            buffer.leer(idx, within, part);
            return;
        }
        ByteRange range = new ByteRange(within, count);
        locator.leerConReubicacion(idx, block -> services.fetcher().leerRango(block, range, () -> readToken,
                (offset, data) -> part.put((int) (offset - within), data, data.position(), data.remaining())));
    }

    @Override
    public synchronized void escribir(long offset, ByteBuffer source) {
        asegurarActivo();
        if (!mode.permiteEscritura()) {
            throw new InvalidOperationException("READ_ONLY", "El archivo está abierto solo para lectura");
        }
        long position = mode == OpenMode.APPEND ? size : offset;
        if (position < 0 || position > size) {
            throw new InvalidOperationException("INVALID_OFFSET", "No se admiten huecos: el archivo mide " + size);
        }
        while (source.hasRemaining()) {
            int idx = (int) (position / blockSize);
            long within = position % blockSize;
            int count = (int) Math.min(source.remaining(), blockSize - within);
            prepararBloque(idx, within, count);
            buffer.escribir(idx, within, source.slice(source.position(), count));
            source.position(source.position() + count);
            dirty.add(idx);
            position += count;
            size = Math.max(size, position);
        }
    }

    /** Copy-on-write: un bloque tocado a medias se trae completo y verificado antes de modificarlo. */
    private void prepararBloque(int idx, long within, int count) {
        if (buffer.contiene(idx)) {
            return;
        }
        buffer.crear(idx);
        BlockInfo existing = locator.obtener(idx);
        if (existing == null || (within == 0 && count >= existing.len())) {
            return;
        }
        try {
            locator.leerConReubicacion(idx, block -> services.fetcher().leerBloque(block, () -> readToken,
                    (offset, data) -> buffer.escribir(idx, offset, data)));
        } catch (RuntimeException e) {
            buffer.descartar(idx);
            throw e;
        }
    }

    @Override
    public synchronized long volcar() {
        asegurarActivo();
        if (!tieneCambiosPendientes()) {
            return locator.obtenerVersion();
        }
        String fencingToken = obtenerTokenDeCerco();
        List<DirtyBlockUploader.Uploaded> uploaded = services.uploader().subir(handle, dirty, buffer);
        List<CommittedBlock> committed = uploaded.stream().map(DirtyBlockUploader.Uploaded::block).toList();
        long version = services.files().confirmar(handle,
                new CommitRequest(fencingToken, size, calcularHash(committed), committed));
        aplicarPublicacion(version, uploaded);
        return version;
    }

    /** En {@code rw} hay otros escritores: solo el servidor conoce el mapa final, y él calcula el hash. */
    private String calcularHash(List<CommittedBlock> committed) {
        if (mode == OpenMode.READ_WRITE) {
            return null;
        }
        FileHash hash = new FileHash();
        locator.listar().forEach(block -> hash.agregarBloque(block.idx(), block.sha256()));
        committed.forEach(block -> hash.agregarBloque(block.idx(), block.sha256()));
        return hash.calcular();
    }

    private void aplicarPublicacion(long version, List<DirtyBlockUploader.Uploaded> uploaded) {
        List<BlockInfo> published = uploaded.stream().map(this::crearBloquePublicado).toList();
        locator.publicar(version, published);
        dirty.clear();
        truncatePending = false;
    }

    private BlockInfo crearBloquePublicado(DirtyBlockUploader.Uploaded item) {
        CommittedBlock block = item.block();
        List<ReplicaRef> replicas = item.pipeline().stream()
                .map(node -> new ReplicaRef(node.nodeId(), node.clientAddr(), null))
                .toList();
        return new BlockInfo(block.idx(), block.blockId(), block.gen(), block.len(), block.sha256(), replicas);
    }

    private String obtenerTokenDeCerco() {
        if (mode != OpenMode.READ_WRITE) {
            return leaseFencingToken;
        }
        int first = dirty.first();
        int last = dirty.last();
        return ownLocks.stream()
                .filter(lock -> lock.cubreParaEscritura(first, last))
                .map(LockHandle::fencingToken)
                .findFirst()
                .orElseThrow(() -> new InvalidOperationException("LOCK_REQUIRED",
                        "El modo rw exige un lock exclusivo que cubra los bloques " + first + "-" + last));
    }

    @Override
    public synchronized LockHandle bloquear(LockMode lockMode, BlockRange range, Duration ttl, Duration wait) {
        asegurarActivo();
        LockHandle lock = services.locks().bloquear(
                new LockRequest(path, lockMode.name(), range, ttl.toMillis(), wait.toMillis()));
        ownLocks.add(lock);
        services.keeper().registrarBloqueo(lock.lockId());
        return lock;
    }

    @Override
    public synchronized void desbloquear(String lockId) {
        services.keeper().quitarBloqueo(lockId);
        services.locks().desbloquear(lockId);
        ownLocks.removeIf(lock -> lock.lockId().equals(lockId));
    }

    @Override
    public synchronized long obtenerTamanio() {
        return size;
    }

    @Override
    public String obtenerRuta() {
        return path;
    }

    @Override
    public synchronized boolean tieneCambiosPendientes() {
        return !dirty.isEmpty() || truncatePending;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            if (lost == null) {
                volcar();
            }
        } finally {
            closed = true;
            liberar();
        }
    }

    private void liberar() {
        services.keeper().quitarHandle(handle);
        List.copyOf(ownLocks).forEach(lock -> ignorarFallos(() -> desbloquear(lock.lockId())));
        ignorarFallos(() -> services.files().cerrar(handle));
        buffer.close();
    }

    private void ignorarFallos(Runnable action) {
        try {
            action.run();
        } catch (DfsException e) {
            // El lease y los locks vencen solos en el servidor.
        }
    }

    private void asegurarActivo() {
        if (lost != null) {
            throw lost;
        }
        if (closed) {
            throw new InvalidOperationException("FILE_CLOSED", "El archivo ya está cerrado");
        }
    }

    @Override
    public void actualizarTokenLectura(String token) {
        readToken = token;
    }

    @Override
    public void notificarPerdida(DfsException cause) {
        lost = cause;
    }
}
