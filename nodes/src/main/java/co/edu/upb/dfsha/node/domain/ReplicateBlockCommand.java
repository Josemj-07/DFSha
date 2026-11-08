package co.edu.upb.dfsha.node.domain;

/** Copiar un bloque local a otro nodo, con el token de escritura que emitió el servidor. */
public record ReplicateBlockCommand(String commandId, BlockKey key, String targetNodeId, String targetAddr,
                                    String token) implements NodeCommand {
}
