package co.edu.upb.dfsha.client.sdk.error;

/** Error del sistema, con un código estable y un mensaje listo para el usuario. */
public abstract sealed class DfsException extends RuntimeException
        permits AuthException, NotFoundException, ConflictException, UnavailableException,
                QuotaException, DataIntegrityException, InvalidOperationException {

    private final String code;

    protected DfsException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String obtenerCodigo() {
        return code;
    }
}
