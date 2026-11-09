package co.edu.upb.dfsha.client.metadata;

/** Operaciones de sesión del usuario. */
public interface AuthGateway {

    LoginResult iniciarSesion(String username, char[] password);

    LoginResult renovarSesion(String refreshToken);

    /** El servidor exige el token de acceso: solo el dueño de la sesión puede revocar su refresh token. */
    void cerrarSesion(String accessToken, String refreshToken);
}
