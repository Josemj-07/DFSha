package co.edu.upb.dfsha.client.data;

/** Una réplica entregó datos que no superan la verificación. */
public final class CorruptReplicaException extends RuntimeException {

    public CorruptReplicaException(String message) {
        super(message);
    }
}
