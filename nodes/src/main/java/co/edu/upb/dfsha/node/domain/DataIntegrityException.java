package co.edu.upb.dfsha.node.domain;

/** Los datos no coinciden con su checksum o su longitud. Se traduce a DATA_LOSS con el offset. */
public class DataIntegrityException extends RuntimeException {

    private final long offset;

    public DataIntegrityException(String message, long offset) {
        super(message + " (offset " + offset + ")");
        this.offset = offset;
    }

    public long obtenerOffset() {
        return offset;
    }
}
