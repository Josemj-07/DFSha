package co.edu.upb.dfsha.client.metadata;

import java.time.Duration;

/**
 * Petición REST.
 *
 * @param idempotent si se puede repetir sin riesgo tras un fallo de conexión
 * @param authenticated si lleva token de sesión
 */
public record RestRequest(String method, String path, Object body, boolean idempotent,
                          boolean authenticated, Duration timeout) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public static RestRequest consultar(String path) {
        return new RestRequest("GET", path, null, true, true, DEFAULT_TIMEOUT);
    }

    public static RestRequest enviar(String path, Object body) {
        return new RestRequest("POST", path, body, false, true, DEFAULT_TIMEOUT);
    }

    public static RestRequest enviarIdempotente(String path, Object body) {
        return new RestRequest("POST", path, body, true, true, DEFAULT_TIMEOUT);
    }

    public static RestRequest borrar(String path) {
        return new RestRequest("DELETE", path, null, false, true, DEFAULT_TIMEOUT);
    }

    public static RestRequest actualizar(String path, Object body) {
        return new RestRequest("PUT", path, body, true, true, DEFAULT_TIMEOUT);
    }

    public RestRequest sinAutenticar() {
        return new RestRequest(method, path, body, idempotent, false, timeout);
    }

    public RestRequest conTiempoLimite(Duration newTimeout) {
        return new RestRequest(method, path, body, idempotent, authenticated, newTimeout);
    }
}
