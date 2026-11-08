package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.domain.ReplicateBlockCommand;
import co.edu.upb.dfsha.node.port.BlockReplicator;
import co.edu.upb.dfsha.node.port.BlockStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ReadableByteChannel;

/**
 * Re-replicación: cuando un nodo muere, el servidor ordena a otro que tenga el bloque que lo
 * copie a un tercero, para volver a tener dos réplicas.
 */
public class ReplicateBlockService implements CommandHandler<ReplicateBlockCommand> {

    private final BlockStore store;
    private final BlockReplicator replicador;

    public ReplicateBlockService(BlockStore store, BlockReplicator replicador) {
        this.store = store;
        this.replicador = replicador;
    }

    @Override
    public void ejecutar(ReplicateBlockCommand comando) {
        BlockMeta meta = store.buscar(comando.key()).orElseThrow(
                () -> new BlockNotFoundException("no se puede replicar " + comando.key().obtenerAlcance()
                        + ": no está en este nodo"));
        try (ReadableByteChannel datos = store.abrir(comando.key(), 0)) {
            replicador.replicar(meta, datos, comando.targetNodeId(), comando.targetAddr(), comando.token());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
