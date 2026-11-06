package co.edu.upb.dfsha.client.data;

/** Una réplica no entregó el bloque: caída, sin respuesta o sin ese bloque. */
public final class ReplicaReadException extends RuntimeException {

    public ReplicaReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
