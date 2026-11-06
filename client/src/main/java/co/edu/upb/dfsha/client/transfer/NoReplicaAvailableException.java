package co.edu.upb.dfsha.client.transfer;

/** Ninguna réplica conocida pudo entregar el bloque. */
public final class NoReplicaAvailableException extends RuntimeException {

    NoReplicaAvailableException(String message) {
        super(message);
    }
}
