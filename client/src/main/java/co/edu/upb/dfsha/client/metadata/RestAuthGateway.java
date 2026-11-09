package co.edu.upb.dfsha.client.metadata;

import java.util.Map;

/** Sesión por REST. Login y refresh no llevan token; logout recibe el vigente de quien lo llama. */
public final class RestAuthGateway implements AuthGateway {

    private final RestInvoker rest;

    public RestAuthGateway(RestInvoker rest) {
        this.rest = rest;
    }

    @Override
    public LoginResult iniciarSesion(String username, char[] password) {
        var body = Map.of("username", username, "password", new String(password));
        return rest.ejecutar(RestRequest.enviar("/auth/login", body).sinAutenticar(), LoginResult.class);
    }

    @Override
    public LoginResult renovarSesion(String refreshToken) {
        var body = Map.of("refreshToken", refreshToken);
        return rest.ejecutar(RestRequest.enviar("/auth/refresh", body).sinAutenticar(), LoginResult.class);
    }

    @Override
    public void cerrarSesion(String accessToken, String refreshToken) {
        var body = Map.of("refreshToken", refreshToken);
        rest.ejecutarSinRespuesta(RestRequest.enviar("/auth/logout", body).sinAutenticar(), accessToken);
    }
}
