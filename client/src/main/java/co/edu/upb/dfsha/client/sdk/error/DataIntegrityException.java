package co.edu.upb.dfsha.client.sdk.error;

/** Los datos no superaron una verificación de integridad o durabilidad. */
public non-sealed class DataIntegrityException extends DfsException {

    public DataIntegrityException(String code, String message) {
        this(code, message, null);
    }

    public DataIntegrityException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
