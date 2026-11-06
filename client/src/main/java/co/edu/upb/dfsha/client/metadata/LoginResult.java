package co.edu.upb.dfsha.client.metadata;

/** Respuesta de login y refresh. {@code expiresIn} va en segundos. */
public record LoginResult(String accessToken, String refreshToken, long expiresIn) {
}
