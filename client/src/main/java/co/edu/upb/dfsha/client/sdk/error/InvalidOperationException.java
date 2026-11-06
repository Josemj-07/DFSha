package co.edu.upb.dfsha.client.sdk.error;

/** La operación no es válida en el estado actual del cliente. */
public final class InvalidOperationException extends DfsException {

    public InvalidOperationException(String code, String message) {
        this(code, message, null);
    }

    public InvalidOperationException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
