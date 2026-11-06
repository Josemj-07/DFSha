package co.edu.upb.dfsha.client.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.upb.dfsha.client.sdk.error.AuthException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestInvokerTest {

    private record Reply(int status, String body) {
    }

    private record Value(String name) {
    }

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<String> authorizations = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private HttpServer server;
    private RestInvoker rest;
    private int renewals;

    private final TokenSource tokens = new TokenSource() {
        @Override
        public String obtenerToken() {
            return "viejo";
        }

        @Override
        public String renovarTrasRechazo(String rejectedToken) {
            renewals++;
            return "nuevo";
        }
    };

    @BeforeEach
    void iniciar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::responder);
        server.start();
        var retries = new RetryPolicy(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps::add);
        rest = new RestInvoker(HttpClient.newHttpClient(), "http://localhost:" + server.getAddress().getPort(),
                tokens, retries);
    }

    @AfterEach
    void detener() {
        server.stop(0);
    }

    private void responder(HttpExchange exchange) throws IOException {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        Reply reply = replies.remove();
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @Test
    void renuevaUnaVezAnteUn401YRepite() {
        replies.add(new Reply(401, "{\"code\":\"INVALID_TOKEN\"}"));
        replies.add(new Reply(200, "{\"name\":\"ok\",\"extra\":1}"));
        assertEquals("ok", rest.ejecutar(RestRequest.consultar("/x"), Value.class).name());
        assertEquals(1, renewals);
        assertEquals(List.of("Bearer viejo", "Bearer nuevo"), authorizations);
    }

    @Test
    void noRenuevaDosVeces() {
        replies.add(new Reply(401, "{\"code\":\"INVALID_TOKEN\"}"));
        replies.add(new Reply(401, "{\"code\":\"INVALID_TOKEN\"}"));
        assertThrows(AuthException.class, () -> rest.ejecutar(RestRequest.consultar("/x"), Value.class));
        assertEquals(1, renewals);
    }

    @Test
    void reintentaEnModoSeguro() {
        replies.add(new Reply(503, "{\"code\":\"SAFE_MODE\"}"));
        replies.add(new Reply(503, "{\"code\":\"SAFE_MODE\"}"));
        replies.add(new Reply(200, "{\"name\":\"ok\"}"));
        assertEquals("ok", rest.ejecutar(RestRequest.enviar("/x", Map.of()), Value.class).name());
        assertEquals(2, sleeps.size());
    }

    @Test
    void lasRutasDeSesionNoLlevanToken() {
        replies.add(new Reply(204, ""));
        rest.ejecutarSinRespuesta(RestRequest.enviar("/auth/logout", Map.of()).sinAutenticar());
        assertEquals(1, authorizations.size());
        assertEquals(null, authorizations.get(0));
    }
}
