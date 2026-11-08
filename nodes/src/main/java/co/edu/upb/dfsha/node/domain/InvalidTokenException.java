package co.edu.upb.dfsha.node.domain;

/** Block token ausente, vencido, mal firmado o con otra audiencia. Se traduce a UNAUTHENTICATED. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
