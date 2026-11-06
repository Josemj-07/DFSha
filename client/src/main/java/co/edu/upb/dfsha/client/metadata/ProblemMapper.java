package co.edu.upb.dfsha.client.metadata;

import co.edu.upb.dfsha.client.sdk.error.AuthException;
import co.edu.upb.dfsha.client.sdk.error.ConflictException;
import co.edu.upb.dfsha.client.sdk.error.DataIntegrityException;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import co.edu.upb.dfsha.client.sdk.error.NotFoundException;
import co.edu.upb.dfsha.client.sdk.error.QuotaException;
import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

/** Traduce una respuesta {@code application/problem+json} a una excepción, por su campo {@code code}. */
final class ProblemMapper {

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("INVALID_CREDENTIALS", "Usuario o contraseña incorrectos"),
            Map.entry("TOO_MANY_ATTEMPTS", "Demasiados intentos fallidos. Espera unos minutos"),
            Map.entry("INVALID_TOKEN", "Sesión vencida, ejecuta dfs login"),
            Map.entry("FORBIDDEN", "No tienes permiso para esta operación"),
            Map.entry("NOT_FOUND", "No existe"),
            Map.entry("ALREADY_EXISTS", "Ya existe"),
            Map.entry("DIR_NOT_EMPTY", "El directorio no está vacío"),
            Map.entry("QUOTA_EXCEEDED", "Superaste tu cuota de almacenamiento"),
            Map.entry("LOCK_CONFLICT", "El recurso está bloqueado por otro cliente"),
            Map.entry("VERSION_CONFLICT", "El archivo cambió desde que lo abriste. Vuelve a leerlo"),
            Map.entry("STALE_FENCING_TOKEN", "Perdiste el lock: otro cliente tomó el archivo"),
            Map.entry("HANDLE_EXPIRED", "La sesión del archivo venció"),
            Map.entry("BLOCK_NOT_DURABLE", "El servidor no pudo confirmar la durabilidad de los bloques"),
            Map.entry("INSUFFICIENT_NODES", "No hay nodos suficientes para escribir"),
            Map.entry("SAFE_MODE", "El servidor se está recuperando. Intenta en unos segundos"));

    private final ObjectMapper json;

    ProblemMapper(ObjectMapper json) {
        this.json = json;
    }

    DfsException mapear(int status, String body) {
        JsonNode problem = leer(body);
        String code = problem.path("code").asText(codigoPorEstado(status));
        String detail = problem.path("detail").asText("");
        String message = MESSAGES.getOrDefault(code, detail.isBlank() ? "Error del servidor (" + status + ")" : detail);
        if (status == 404 && !detail.isBlank()) {
            message = message + ": " + detail;
        }
        return crear(status, code, message);
    }

    private DfsException crear(int status, String code, String message) {
        return switch (status) {
            case 401, 403, 429 -> new AuthException(code, message);
            case 404 -> new NotFoundException(code, message);
            case 409, 410 -> new ConflictException(code, message);
            case 422 -> new DataIntegrityException(code, message);
            case 507 -> new QuotaException(code, message);
            default -> status >= 500
                    ? new UnavailableException(code, message)
                    : new InvalidOperationException(code, message);
        };
    }

    private String codigoPorEstado(int status) {
        return status >= 500 ? "SERVER_ERROR" : "HTTP_" + status;
    }

    private JsonNode leer(String body) {
        try {
            JsonNode node = json.readTree(body == null ? "" : body);
            return node == null ? json.createObjectNode() : node;
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }
}
