package co.edu.upb.dfsha.client.sdk.error;

/** El usuario superó su cuota. */
public final class QuotaException extends DfsException {

    public QuotaException(String code, String message) {
        this(code, message, null);
    }

    public QuotaException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
