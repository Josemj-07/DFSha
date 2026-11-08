package co.edu.upb.dfsha.node.port;

import co.edu.upb.dfsha.node.domain.BlockMeta;

import java.nio.channels.ReadableByteChannel;

/** Copia un bloque completo a otro nodo. */
public interface BlockReplicator {

    /** Lanza ReplicationFailedException si no se pudo; si el destino ya lo tenía, termina sin error. */
    void replicar(BlockMeta meta, ReadableByteChannel datos, String targetNodeId, String targetAddr, String token);
}
