package co.edu.upb.dfsha.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.upb.dfsha.client.metadata.AuthGateway;
import co.edu.upb.dfsha.client.metadata.LoginResult;
import co.edu.upb.dfsha.client.sdk.error.AuthException;
import co.edu.upb.dfsha.client.sdk.error.NotAuthenticatedException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenManagerTest {

    private static final String URL = "https://s:1";

    @TempDir
    Path dir;

    private final AtomicInteger refreshes = new AtomicInteger();
    private boolean rejectRefresh;
    private boolean rejectLogout;
    private final List<String> logouts = new ArrayList<>();
    private SessionStore store;
    private TokenManager tokens;

    private final AuthGateway auth = new AuthGateway() {
        @Override
        public LoginResult iniciarSesion(String username, char[] password) {
            return new LoginResult("acc-0", "ref-0", 900);
        }

        @Override
        public LoginResult renovarSesion(String refreshToken) {
            if (rejectRefresh) {
                throw new AuthException("INVALID_TOKEN", "vencido");
            }
            int count = refreshes.incrementAndGet();
            return new LoginResult("acc-" + count, "ref-" + count, 900);
        }

        @Override
        public void cerrarSesion(String accessToken, String refreshToken) {
            logouts.add(accessToken + "/" + refreshToken);
            if (rejectLogout) {
                throw new AuthException("INVALID_TOKEN", "rechazado");
            }
        }
    };

    @BeforeEach
    void iniciar() {
        store = new SessionStore(dir, message -> { });
        tokens = crear(Instant.EPOCH);
    }

    private TokenManager crear(Instant now) {
        return new TokenManager(auth, store, URL, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void entregaElTokenVigenteSinRenovar() {
        tokens.iniciarSesion("jose", new char[0]);
        assertEquals("acc-0", tokens.obtenerToken());
        assertEquals(0, refreshes.get());
        assertEquals("/home/jose", tokens.obtenerSesion().cwd());
    }

    @Test
    void renuevaSiFaltanMenosDe30Segundos() {
        tokens.iniciarSesion("jose", new char[0]);
        assertEquals("acc-1", crear(Instant.ofEpochSecond(880)).obtenerToken());
        assertEquals("ref-1", store.cargar().orElseThrow().refreshToken());
    }

    @Test
    void siElRefreshFallaPideIniciarSesion() {
        tokens.iniciarSesion("jose", new char[0]);
        rejectRefresh = true;
        var error = assertThrows(NotAuthenticatedException.class, () -> tokens.renovarTrasRechazo("acc-0"));
        assertEquals("Sesión vencida, ejecuta dfs login", error.getMessage());
    }

    @Test
    void elLogoutUsaElRefreshRotadoSiTuvoQueRenovar() {
        tokens.iniciarSesion("jose", new char[0]);
        crear(Instant.ofEpochSecond(880)).cerrarSesion();
        assertEquals(List.of("acc-1/ref-1"), logouts);
        assertTrue(store.cargar().isEmpty());
    }

    @Test
    void elLogoutBorraLaSesionLocalAunqueElServidorLoRechace() {
        tokens.iniciarSesion("jose", new char[0]);
        rejectLogout = true;
        assertThrows(AuthException.class, tokens::cerrarSesion);
        assertTrue(store.cargar().isEmpty());
    }

    @Test
    void unaSesionDeOtroServidorNoSirve() {
        tokens.iniciarSesion("jose", new char[0]);
        var other = new TokenManager(auth, store, "https://otro:1", Clock.systemUTC());
        assertThrows(NotAuthenticatedException.class, other::obtenerToken);
    }

    @Test
    void variosHilosRechazadosRenuevanUnaSolaVez() throws InterruptedException {
        tokens.iniciarSesion("jose", new char[0]);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    tokens.renovarTrasRechazo("acc-0");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        assertEquals(1, refreshes.get());
    }
}
