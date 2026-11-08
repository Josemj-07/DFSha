package co.edu.upb.dfsha.node.domain;

/** No se pudo copiar un bloque a otro nodo. El servidor reintenta la reparación por su cuenta. */
public class ReplicationFailedException extends RuntimeException {

    public ReplicationFailedException(String message) {
        super(message);
    }
}
