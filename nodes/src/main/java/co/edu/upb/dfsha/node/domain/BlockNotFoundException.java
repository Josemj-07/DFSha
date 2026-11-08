package co.edu.upb.dfsha.node.domain;

/** El bloque no existe, está en cuarentena o es de otra generación. Se traduce a NOT_FOUND. */
public class BlockNotFoundException extends RuntimeException {

    public BlockNotFoundException(String message) {
        super(message);
    }
}
