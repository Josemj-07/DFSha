package co.edu.upb.dfsha.client.transfer;

import co.edu.upb.dfsha.client.metadata.AllocatedBlock;
import co.edu.upb.dfsha.client.metadata.Allocation;
import co.edu.upb.dfsha.client.metadata.CommittedBlock;
import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.PipelineNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentHashMap;

/** Sube los bloques modificados de un archivo abierto, agrupando índices consecutivos por asignación. */
public final class DirtyBlockUploader {

    /** Resultado de subir un bloque, con la cadena donde quedó para poder releerlo. */
    public record Uploaded(CommittedBlock block, List<PipelineNode> pipeline) {
    }

    private final FileSessionGateway files;
    private final BlockPublisher publisher;
    private final TransferOptions options;

    public DirtyBlockUploader(FileSessionGateway files, BlockPublisher publisher, TransferOptions options) {
        this.files = files;
        this.publisher = publisher;
        this.options = options;
    }

    public List<Uploaded> subir(String handle, NavigableSet<Integer> dirty, BlockBuffer buffer) {
        Map<Integer, Uploaded> done = new ConcurrentHashMap<>();
        try (ParallelTasks tasks = new ParallelTasks(options.parallelism())) {
            for (List<Integer> run : agrupar(dirty)) {
                Allocation allocation = files.asignar(handle, run.get(0), run.size());
                for (AllocatedBlock block : allocation.blocks()) {
                    tasks.enviar(() -> done.put(block.idx(),
                            subirBloque(handle, block, allocation.writeToken(), buffer)));
                }
            }
            tasks.esperar();
        }
        return done.values().stream().sorted(Comparator.comparingInt(item -> item.block().idx())).toList();
    }

    private Uploaded subirBloque(String handle, AllocatedBlock block, String token, BlockBuffer buffer) {
        long length = buffer.obtenerLongitud(block.idx());
        CommittedBlock committed = publisher.publicar(handle, block, token, length,
                () -> new FileBlockSource(buffer.obtenerRuta(block.idx()), 0, length, options.chunkSize()));
        return new Uploaded(committed, block.pipeline());
    }

    private List<List<Integer>> agrupar(NavigableSet<Integer> dirty) {
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> run = new ArrayList<>();
        for (int idx : dirty) {
            boolean contiguous = !run.isEmpty() && run.get(run.size() - 1) == idx - 1;
            if (!run.isEmpty() && (!contiguous || run.size() == options.allocationBatch())) {
                runs.add(run);
                run = new ArrayList<>();
            }
            run.add(idx);
        }
        if (!run.isEmpty()) {
            runs.add(run);
        }
        return runs;
    }
}
