package co.edu.upb.dfsha.client.sdk.error;

/** Credenciales rechazadas, sesión vencida o falta de permisos. */
public non-sealed class AuthException extends DfsException {

    public AuthException(String code, String message) {
        this(code, message, null);
    }

    public AuthException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
