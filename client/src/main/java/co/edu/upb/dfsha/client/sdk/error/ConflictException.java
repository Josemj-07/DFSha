package co.edu.upb.dfsha.client.sdk.error;

/** La operación choca con el estado actual: versión, lock o existencia. */
public final class ConflictException extends DfsException {

    public ConflictException(String code, String message) {
        this(code, message, null);
    }

    public ConflictException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
