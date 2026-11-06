package co.edu.upb.dfsha.client.metadata;

/** Operaciones de sesión del usuario. */
public interface AuthGateway {

    LoginResult iniciarSesion(String username, char[] password);

    LoginResult renovarSesion(String refreshToken);

    void cerrarSesion(String refreshToken);
}
