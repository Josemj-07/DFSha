package co.edu.upb.dfsha.client.session;

import co.edu.upb.dfsha.client.metadata.AuthGateway;
import co.edu.upb.dfsha.client.metadata.LoginResult;
import co.edu.upb.dfsha.client.metadata.TokenSource;
import co.edu.upb.dfsha.client.sdk.error.AuthException;
import co.edu.upb.dfsha.client.sdk.error.NotAuthenticatedException;
import java.time.Clock;
import java.time.Duration;

/**
 * Entrega el token de acceso y lo renueva. La renovación se sincroniza porque el refresh token rota:
 * dos hilos renovando a la vez invalidarían la sesión.
 */
public final class TokenManager implements TokenSource {

    private static final Duration RENEWAL_MARGIN = Duration.ofSeconds(30);
    private static final String EXPIRED_MESSAGE = "Sesión vencida, ejecuta dfs login";

    private final AuthGateway auth;
    private final SessionStore store;
    private final String serverUrl;
    private final Clock clock;

    public TokenManager(AuthGateway auth, SessionStore store, String serverUrl, Clock clock) {
        this.auth = auth;
        this.store = store;
        this.serverUrl = serverUrl;
        this.clock = clock;
    }

    public void iniciarSesion(String username, char[] password) {
        LoginResult login = auth.iniciarSesion(username, password);
        store.guardar(new Session(serverUrl, username, login.accessToken(), login.refreshToken(),
                calcularVencimiento(login), "/home/" + username));
    }

    public void cerrarSesion() {
        try {
            if (store.cargar().isPresent()) {
                // Primero el token: si se renueva, el refresh token rota y hay que leerlo después.
                String access = obtenerToken();
                auth.cerrarSesion(access, obtenerSesion().refreshToken());
            }
        } finally {
            // Aunque el servidor no responda, la sesión local no debe sobrevivir a un logout.
            store.borrar();
        }
    }

    public Session obtenerSesion() {
        return store.cargar()
                .filter(session -> serverUrl.equals(session.serverUrl()))
                .orElseThrow(() -> new NotAuthenticatedException("No has iniciado sesión. Ejecuta dfs login"));
    }

    @Override
    public synchronized String obtenerToken() {
        Session session = obtenerSesion();
        long limit = clock.millis() + RENEWAL_MARGIN.toMillis();
        return session.accessExpiresAt() > limit ? session.accessToken() : renovar(session);
    }

    @Override
    public synchronized String renovarTrasRechazo(String rejectedToken) {
        Session session = obtenerSesion();
        return session.accessToken().equals(rejectedToken) ? renovar(session) : session.accessToken();
    }

    private String renovar(Session session) {
        try {
            LoginResult login = auth.renovarSesion(session.refreshToken());
            store.guardar(session.conTokens(login.accessToken(), login.refreshToken(), calcularVencimiento(login)));
            return login.accessToken();
        } catch (AuthException e) {
            throw new NotAuthenticatedException(EXPIRED_MESSAGE);
        }
    }

    private long calcularVencimiento(LoginResult login) {
        return clock.millis() + Duration.ofSeconds(login.expiresIn()).toMillis();
    }
}
