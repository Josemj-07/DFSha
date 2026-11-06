package co.edu.upb.dfsha.client.sdk.model;

/** Ubicación de una réplica de un bloque. */
public record ReplicaRef(String nodeId, String addr, String az) {
}
