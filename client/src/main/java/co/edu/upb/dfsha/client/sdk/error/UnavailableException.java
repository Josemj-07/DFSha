package co.edu.upb.dfsha.client.sdk.error;

/** El servidor o los nodos no pueden atender la operación. */
public final class UnavailableException extends DfsException {

    public UnavailableException(String code, String message) {
        this(code, message, null);
    }

    public UnavailableException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
