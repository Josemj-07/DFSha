package co.edu.upb.dfsha.client.metadata;

/** Respuesta de keepalive; {@code readToken} es nulo si el handle no lee. */
public record KeepaliveResult(long ttlMs, String readToken) {
}
