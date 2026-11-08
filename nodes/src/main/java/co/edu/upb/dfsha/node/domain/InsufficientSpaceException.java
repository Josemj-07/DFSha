package co.edu.upb.dfsha.node.domain;

/** No hay disco para el bloque. Se traduce a RESOURCE_EXHAUSTED. */
public class InsufficientSpaceException extends RuntimeException {

    public InsufficientSpaceException(String message) {
        super(message);
    }
}
