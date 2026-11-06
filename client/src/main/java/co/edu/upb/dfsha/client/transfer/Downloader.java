package co.edu.upb.dfsha.client.transfer;

import co.edu.upb.dfsha.client.integrity.Checksums;
import co.edu.upb.dfsha.client.integrity.FileHash;
import co.edu.upb.dfsha.client.metadata.BlockInfo;
import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.OpenRequest;
import co.edu.upb.dfsha.client.metadata.OpenResult;
import co.edu.upb.dfsha.client.sdk.error.DataIntegrityException;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import co.edu.upb.dfsha.client.sdk.model.ProgressListener;
import co.edu.upb.dfsha.client.session.LeaseKeeper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Descarga un archivo completo a un temporal y solo lo publica en el destino tras verificarlo. */
public final class Downloader {

    private static final int MAX_OPENS = 2;

    private final FileSessionGateway files;
    private final BlockFetcher fetcher;
    private final LeaseKeeper keeper;
    private final TransferOptions options;

    public Downloader(FileSessionGateway files, BlockFetcher fetcher, LeaseKeeper keeper, TransferOptions options) {
        this.files = files;
        this.fetcher = fetcher;
        this.keeper = keeper;
        this.options = options;
    }

    public void descargar(String remote, Path local, ProgressListener progress) {
        Path temp = local.resolveSibling(local.getFileName() + ".dfspart");
        try {
            Files.deleteIfExists(temp);
            descargarConReapertura(remote, temp, progress);
            Files.move(temp, local, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir " + local, e);
        } finally {
            borrar(temp);
        }
    }

    /** Sin réplicas pide ubicaciones nuevas una vez, conservando los bloques ya descargados. */
    private void descargarConReapertura(String remote, Path temp, ProgressListener progress) throws IOException {
        Set<Integer> done = ConcurrentHashMap.newKeySet();
        long version = -1;
        for (int open = 1; ; open++) {
            OpenResult opened = files.abrir(new OpenRequest(remote, "r", false, false));
            if (opened.baseVersion() != version) {
                done.clear();
                version = opened.baseVersion();
            }
            try {
                descargarVersion(opened, temp, done, progress);
                verificarArchivo(opened);
                return;
            } catch (NoReplicaAvailableException e) {
                if (open >= MAX_OPENS) {
                    throw new UnavailableException("NO_REPLICAS", e.getMessage(), e);
                }
            } finally {
                keeper.quitarHandle(opened.handle());
                cerrar(opened.handle());
            }
        }
    }

    private void descargarVersion(OpenResult opened, Path temp, Set<Integer> done, ProgressListener progress)
            throws IOException {
        AtomicReference<String> token = new AtomicReference<>(opened.readToken());
        try (FileChannel output = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             ParallelTasks tasks = new ParallelTasks(options.parallelism())) {
            keeper.registrarHandle(opened.handle(), crearEscucha(token, tasks));
            int total = opened.blocks().size();
            for (BlockInfo block : opened.blocks()) {
                if (!done.contains(block.idx())) {
                    tasks.enviar(() -> {
                        descargarBloque(block, opened.blockSize(), token, output);
                        done.add(block.idx());
                        progress.notificarBloque(done.size(), total);
                    });
                }
            }
            tasks.esperar();
            output.truncate(opened.size());
        }
    }

    private void descargarBloque(BlockInfo block, long blockSize, AtomicReference<String> token, FileChannel output) {
        long base = block.idx() * blockSize;
        fetcher.leerBloque(block, token::get, (offset, data) -> {
            long position = base + offset;
            while (data.hasRemaining()) {
                position += output.write(data, position);
            }
        });
    }

    private void verificarArchivo(OpenResult opened) {
        FileHash hash = new FileHash();
        opened.blocks().forEach(block -> hash.agregarBloque(block.idx(), block.sha256()));
        if (opened.fileSha256() != null && !Checksums.sonIguales(hash.calcular(), opened.fileSha256())) {
            throw new DataIntegrityException("FILE_HASH_MISMATCH", "El hash del archivo descargado no coincide");
        }
    }

    private LeaseKeeper.HandleListener crearEscucha(AtomicReference<String> token, ParallelTasks tasks) {
        return new LeaseKeeper.HandleListener() {
            @Override
            public void actualizarTokenLectura(String readToken) {
                token.set(readToken);
            }

            @Override
            public void notificarPerdida(DfsException cause) {
                tasks.abortar(cause);
            }
        };
    }

    private void cerrar(String handle) {
        try {
            files.cerrar(handle);
        } catch (DfsException e) {
            // El lease vence solo y la descarga ya terminó.
        }
    }

    private void borrar(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar " + temp, e);
        }
    }
}
