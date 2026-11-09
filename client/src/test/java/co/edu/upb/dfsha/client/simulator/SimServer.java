package co.edu.upb.dfsha.client.simulator;

import co.edu.upb.dfsha.client.simulator.SimMetadata.Problem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;

/** Servidor de metadatos simulado: HTTPS real sobre las rutas del contrato. */
public final class SimServer implements AutoCloseable {

    private static final long TOKEN_SECONDS = 900;

    private final HttpsServer server;
    private final SimApi api;
    private final SimMetadata state;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> passwords = new ConcurrentHashMap<>();
    private final Map<String, String> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();
    private final AtomicInteger safeModeResponses = new AtomicInteger();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private volatile int pageSize = 100;

    public SimServer(SSLContext ssl, String[] protocols, List<SimNode> nodes, long blockSize) throws IOException {
        this.state = new SimMetadata(nodes, blockSize);
        this.api = new SimApi(state);
        this.server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            @Override
            public void configure(HttpsParameters parameters) {
                parameters.setProtocols(protocols);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/", this::atender);
        server.start();
    }

    public String obtenerUrl() {
        return "https://localhost:" + server.getAddress().getPort();
    }

    public void crearUsuario(String username, String password) {
        passwords.put(username, password);
        api.crearDirectorio("/home/" + username, true, username);
    }

    public void simularModoSeguro(int responses) {
        safeModeResponses.set(responses);
    }

    public void vencerAccesos() {
        accessTokens.clear();
    }

    public void establecerTamanioPagina(int size) {
        pageSize = size;
    }

    public void simularPerdidaDeHandles() {
        synchronized (api) {
            state.handles.clear();
        }
    }

    /** Réplicas de un bloque, en el orden en que el cliente las prueba. */
    public List<SimNode> obtenerReplicas(String path, int idx) {
        synchronized (api) {
            return List.copyOf(state.entries.get(path).blocks.get(idx).replicas);
        }
    }

    public List<Map<String, Object>> obtenerReportes() {
        return List.copyOf(state.reports);
    }

    private void atender(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath().substring("/v1".length());
            Object body = enrutar(exchange, path);
            responder(exchange, body == null ? 204 : 200, body);
        } catch (Problem e) {
            responderProblema(exchange, e);
        } catch (RuntimeException e) {
            e.printStackTrace();
            responderProblema(exchange, new Problem(500, "SERVER_ERROR", String.valueOf(e.getMessage())));
        } finally {
            exchange.close();
        }
    }

    private Object enrutar(HttpExchange exchange, String path) throws IOException {
        Map<String, Object> body = leerCuerpo(exchange);
        if (path.equals("/auth/login")) {
            return iniciarSesion(body);
        }
        if (path.equals("/auth/refresh")) {
            return renovar(body);
        }
        if (path.equals("/auth/logout")) {
            return cerrarSesion(exchange, body);
        }
        String user = autenticar(exchange);
        if (path.startsWith("/files") || path.startsWith("/locks")) {
            if (safeModeResponses.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new Problem(503, "SAFE_MODE", "recuperando");
            }
        }
        return despachar(exchange, path, body, user);
    }

    private Object despachar(HttpExchange exchange, String path, Map<String, Object> body, String user) {
        Map<String, String> query = leerConsulta(exchange);
        String method = exchange.getRequestMethod();
        if (path.startsWith("/fs/")) {
            return despacharEspacio(path, method, body, query, user);
        }
        if (path.equals("/files:open")) {
            return api.abrir(body, user);
        }
        if (path.startsWith("/files/")) {
            return despacharArchivo(path.substring("/files/".length()), body);
        }
        if (path.equals("/locks")) {
            return api.bloquear(body);
        }
        if (path.startsWith("/locks/")) {
            return despacharBloqueo(path.substring("/locks/".length()), method);
        }
        if (path.equals("/reports/replica")) {
            api.reportar(body);
            return null;
        }
        if (path.equals("/admin/nodes")) {
            return api.listarNodos();
        }
        if (path.equals("/admin/audit")) {
            return List.of();
        }
        if (path.equals("/admin/users")) {
            crearUsuario((String) body.get("username"), (String) body.get("password"));
            return null;
        }
        if (path.startsWith("/admin/users/")) {
            return null;
        }
        throw new Problem(404, "NOT_FOUND", path);
    }

    private Object despacharEspacio(String path, String method, Map<String, Object> body,
                                    Map<String, String> query, String user) {
        String target = query.get("path");
        switch (path) {
            case "/fs/ls" -> {
                return api.listar(target, query.get("cursor"), pageSize);
            }
            case "/fs/stat" -> {
                return api.atributos(target);
            }
            case "/fs/mkdir" -> api.crearDirectorio((String) body.get("path"),
                    Boolean.TRUE.equals(body.get("parents")), user);
            case "/fs/rmdir" -> api.borrarDirectorio(target, "true".equals(query.get("recursive")));
            case "/fs/files" -> api.borrarArchivo(target);
            case "/fs/move" -> api.mover((String) body.get("from"), (String) body.get("to"));
            case "/fs/copy" -> api.copiar((String) body.get("from"), (String) body.get("to"));
            default -> throw new Problem(404, "NOT_FOUND", path);
        }
        return null;
    }

    private Object despacharArchivo(String action, Map<String, Object> body) {
        int split = action.lastIndexOf(':');
        String handle = action.substring(0, split);
        switch (action.substring(split + 1)) {
            case "allocate" -> {
                return api.asignar(handle, (int) body.get("firstIdx"), (int) body.get("count"));
            }
            case "recover-block" -> {
                return api.recuperar(handle, body);
            }
            case "commit" -> {
                return api.confirmar(handle, body);
            }
            case "keepalive" -> {
                return api.mantener(handle);
            }
            case "close" -> {
                api.cerrar(handle);
                return null;
            }
            default -> throw new Problem(404, "NOT_FOUND", action);
        }
    }

    private Object despacharBloqueo(String action, String method) {
        if (action.endsWith(":renew")) {
            api.renovarBloqueo(action.substring(0, action.length() - ":renew".length()));
        } else if (method.equals("DELETE")) {
            api.desbloquear(action);
        }
        return null;
    }

    private Map<String, Object> iniciarSesion(Map<String, Object> body) {
        String username = (String) body.get("username");
        if (!String.valueOf(body.get("password")).equals(passwords.get(username))) {
            throw new Problem(401, "INVALID_CREDENTIALS", "credenciales");
        }
        return emitirTokens(username);
    }

    private Map<String, Object> renovar(Map<String, Object> body) {
        String username = refreshTokens.remove((String) body.get("refreshToken"));
        if (username == null) {
            throw new Problem(401, "INVALID_TOKEN", "refresh");
        }
        return emitirTokens(username);
    }

    /** Como el servidor real: exige el JWT y que el refresh token sea de ese usuario. */
    private Object cerrarSesion(HttpExchange exchange, Map<String, Object> body) {
        String user = autenticar(exchange);
        String refresh = (String) body.get("refreshToken");
        if (!user.equals(refreshTokens.get(refresh))) {
            throw new Problem(401, "INVALID_TOKEN", "refresh");
        }
        refreshTokens.remove(refresh);
        return null;
    }

    private Map<String, Object> emitirTokens(String username) {
        String access = "acc-" + tokenCounter.incrementAndGet();
        String refresh = "ref-" + tokenCounter.incrementAndGet();
        accessTokens.put(access, username);
        refreshTokens.put(refresh, username);
        return Map.of("accessToken", access, "refreshToken", refresh, "expiresIn", TOKEN_SECONDS);
    }

    private String autenticar(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String user = header == null ? null : accessTokens.get(header.replace("Bearer ", ""));
        if (user == null) {
            throw new Problem(401, "INVALID_TOKEN", "token");
        }
        return user;
    }

    private Map<String, Object> leerCuerpo(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return bytes.length == 0 ? Map.of() : json.readValue(bytes, new TypeReference<>() { });
    }

    private Map<String, String> leerConsulta(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                values.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private void responder(HttpExchange exchange, int status, Object body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void responderProblema(HttpExchange exchange, Problem problem) throws IOException {
        Map<String, Object> body = Map.of("type", "/errors/x", "title", problem.code, "status", problem.status,
                "code", problem.code, "detail", String.valueOf(problem.getMessage()), "traceId", "sim");
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
        exchange.sendResponseHeaders(problem.status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
