package co.edu.upb.dfsha.node.domain;

/** Ese (blockId, gen) ya está completo en el nodo. Se traduce a ALREADY_EXISTS. */
public class BlockAlreadyExistsException extends RuntimeException {

    public BlockAlreadyExistsException(String message) {
        super(message);
    }
}
