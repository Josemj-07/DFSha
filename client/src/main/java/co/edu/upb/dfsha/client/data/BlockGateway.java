package co.edu.upb.dfsha.client.data;

import co.edu.upb.dfsha.client.sdk.model.ReplicaRef;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Transferencia de bloques con los nodos. */
public interface BlockGateway {

    /** Falla con {@link ReplicaWriteException} si la cadena no completa la escritura. */
    CompletableFuture<List<Receipt>> escribir(BlockTarget target, BlockSource source, String token);

    /** Bloquea hasta terminar. Falla con {@link ReplicaReadException} o {@link CorruptReplicaException}. */
    void leer(ReplicaRef replica, String blockId, long gen, ByteRange range, String token, ChunkConsumer consumer);
}
