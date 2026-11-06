package co.edu.upb.dfsha.client.metadata;

/** Reporte de una réplica inalcanzable o corrupta. */
public record ReplicaReport(String blockId, long gen, String node, String reason) {
}
