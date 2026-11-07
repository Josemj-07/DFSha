package co.edu.upb.dfsha.server.cluster.domain;

import java.security.PublicKey;
import java.time.Instant;

public record Node(
    String nodeId,
    String host,
    int clientPort,
    int replicationPort,
    String az,
    long capacityBytes,
    long usedBytes,
    int connections,
    Instant lastContact,
    String status,
    PublicKey publicKey,
    boolean reported) {
  public String obtenerDireccionCliente() {
    return host + ":" + clientPort;
  }

  public String obtenerDireccionReplica() {
    return host + ":" + replicationPort;
  }

  public Node actualizar(
      Instant time, String status, long used, int connections, boolean reported) {
    return new Node(
        nodeId,
        host,
        clientPort,
        replicationPort,
        az,
        capacityBytes,
        used,
        connections,
        time,
        status,
        publicKey,
        reported);
  }
}
