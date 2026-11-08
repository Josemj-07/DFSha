package co.edu.upb.dfsha.node.domain;

/** El token es válido pero no cubre esa operación o ese bloque. Se traduce a PERMISSION_DENIED. */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
