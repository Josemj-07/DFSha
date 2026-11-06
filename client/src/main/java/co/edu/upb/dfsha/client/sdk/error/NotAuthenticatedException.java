package co.edu.upb.dfsha.client.sdk.error;

/** No hay sesión o ya no se puede renovar. */
public final class NotAuthenticatedException extends AuthException {

    public NotAuthenticatedException(String message) {
        super("NOT_AUTHENTICATED", message);
    }
}
