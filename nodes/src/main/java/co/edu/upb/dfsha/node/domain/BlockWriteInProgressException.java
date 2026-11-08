package co.edu.upb.dfsha.node.domain;

/** Otra escritura del mismo bloque está en curso. Se traduce a ABORTED. */
public class BlockWriteInProgressException extends RuntimeException {

    public BlockWriteInProgressException(String message) {
        super(message);
    }
}
