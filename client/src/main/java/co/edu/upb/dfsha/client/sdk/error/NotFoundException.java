package co.edu.upb.dfsha.client.sdk.error;

/** La ruta o el recurso no existe. */
public final class NotFoundException extends DfsException {

    public NotFoundException(String code, String message) {
        this(code, message, null);
    }

    public NotFoundException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
