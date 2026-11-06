package co.edu.upb.dfsha.client.transfer;

import co.edu.upb.dfsha.client.integrity.FileHash;
import co.edu.upb.dfsha.client.metadata.AllocatedBlock;
import co.edu.upb.dfsha.client.metadata.Allocation;
import co.edu.upb.dfsha.client.metadata.CommitRequest;
import co.edu.upb.dfsha.client.metadata.CommittedBlock;
import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.OpenRequest;
import co.edu.upb.dfsha.client.metadata.OpenResult;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import co.edu.upb.dfsha.client.sdk.error.NotFoundException;
import co.edu.upb.dfsha.client.sdk.model.ProgressListener;
import co.edu.upb.dfsha.client.session.LeaseKeeper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Sube un archivo local completo: abre, escribe bloques en paralelo, publica y cierra. */
public final class Uploader {

    private final FileSessionGateway files;
    private final BlockPublisher publisher;
    private final LeaseKeeper keeper;
    private final TransferOptions options;

    public Uploader(FileSessionGateway files, BlockPublisher publisher, LeaseKeeper keeper, TransferOptions options) {
        this.files = files;
        this.publisher = publisher;
        this.keeper = keeper;
        this.options = options;
    }

    public long subir(Path local, String remote, boolean exclusive, ProgressListener progress) {
        long size = medir(local);
        OpenResult opened = files.abrir(new OpenRequest(remote, "w", true, exclusive));
        String handle = opened.handle();
        try (ParallelTasks tasks = new ParallelTasks(options.parallelism())) {
            keeper.registrarHandle(handle, crearEscucha(tasks));
            List<CommittedBlock> blocks = subirBloques(local, size, opened, tasks, progress);
            long version = files.confirmar(handle, crearPublicacion(opened, size, blocks));
            files.cerrar(handle);
            return version;
        } catch (RuntimeException e) {
            cerrarSinPublicar(handle);
            throw e;
        } finally {
            keeper.quitarHandle(handle);
        }
    }

    private List<CommittedBlock> subirBloques(Path local, long size, OpenResult opened, ParallelTasks tasks,
                                              ProgressListener progress) {
        long blockSize = opened.blockSize();
        int total = (int) ((size + blockSize - 1) / blockSize);
        List<CommittedBlock> done = new CopyOnWriteArrayList<>();
        AtomicInteger finished = new AtomicInteger();
        for (int first = 0; first < total; first += options.allocationBatch()) {
            int count = Math.min(options.allocationBatch(), total - first);
            Allocation allocation = files.asignar(opened.handle(), first, count);
            for (AllocatedBlock block : allocation.blocks()) {
                tasks.enviar(() -> {
                    done.add(subirBloque(local, size, opened, block, allocation.writeToken()));
                    progress.notificarBloque(finished.incrementAndGet(), total);
                });
            }
        }
        tasks.esperar();
        return done.stream().sorted(Comparator.comparingInt(CommittedBlock::idx)).toList();
    }

    private CommittedBlock subirBloque(Path local, long size, OpenResult opened, AllocatedBlock block, String token) {
        long start = block.idx() * opened.blockSize();
        long length = Math.min(opened.blockSize(), size - start);
        return publisher.publicar(opened.handle(), block, token, length,
                () -> new FileBlockSource(local, start, length, options.chunkSize()));
    }

    private CommitRequest crearPublicacion(OpenResult opened, long size, List<CommittedBlock> blocks) {
        FileHash hash = new FileHash();
        blocks.forEach(block -> hash.agregarBloque(block.idx(), block.sha256()));
        return new CommitRequest(opened.lease().fencingToken(), size, hash.calcular(), blocks);
    }

    private LeaseKeeper.HandleListener crearEscucha(ParallelTasks tasks) {
        return new LeaseKeeper.HandleListener() {
            @Override
            public void actualizarTokenLectura(String readToken) {
                // Una subida no lee bloques.
            }

            @Override
            public void notificarPerdida(DfsException cause) {
                tasks.abortar(cause);
            }
        };
    }

    private void cerrarSinPublicar(String handle) {
        try {
            files.cerrar(handle);
        } catch (DfsException e) {
            // El lease vence solo: el fallo que importa es el original.
        }
    }

    private long medir(Path local) {
        try {
            if (!Files.isRegularFile(local)) {
                throw new NotFoundException("LOCAL_NOT_FOUND", "No existe el archivo local: " + local);
            }
            return Files.size(local);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + local, e);
        }
    }
}
