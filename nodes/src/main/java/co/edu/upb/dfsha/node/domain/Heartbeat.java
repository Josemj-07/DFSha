package co.edu.upb.dfsha.node.domain;

/** Cuerpo de node.heartbeat.&lt;id&gt;. sentAt va en ISO-8601, como en el nodo de prueba del servidor. */
public record Heartbeat(String nodeId, long capacityBytes, long usedBytes, int activeConnections, int blockCount,
                        String sentAt) {
}
