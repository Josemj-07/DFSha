package co.edu.upb.dfsha.client.metadata;

import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import javax.net.ssl.SSLException;

/** Punto único de salida HTTP: JSON, token, tiempo límite, reintentos y traducción de errores. */
public final class RestInvoker {

    private static final String API_PREFIX = "/v1";

    private final HttpClient http;
    private final String baseUrl;
    private final TokenSource tokens;
    private final RetryPolicy retries;
    private final ObjectMapper json;
    private final ProblemMapper problems;

    public RestInvoker(HttpClient http, String baseUrl, TokenSource tokens, RetryPolicy retries) {
        this.http = http;
        this.baseUrl = baseUrl + API_PREFIX;
        this.tokens = tokens;
        this.retries = retries;
        this.json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.problems = new ProblemMapper(json);
    }

    public <T> T ejecutar(RestRequest request, Class<T> type) {
        return convertir(retries.ejecutar(() -> enviarConRenovacion(request), request.idempotent()),
                json.constructType(type));
    }

    public <T> List<T> ejecutarLista(RestRequest request, Class<T> type) {
        return convertir(retries.ejecutar(() -> enviarConRenovacion(request), request.idempotent()),
                json.getTypeFactory().constructCollectionType(List.class, type));
    }

    public void ejecutarSinRespuesta(RestRequest request) {
        retries.ejecutar(() -> enviarConRenovacion(request), request.idempotent());
    }

    /** Envía con un token dado, sin renovarlo: para quien ya administra la sesión. */
    public void ejecutarSinRespuesta(RestRequest request, String token) {
        retries.ejecutar(() -> leerCuerpo(enviar(request, token)), request.idempotent());
    }

    private String enviarConRenovacion(RestRequest request) {
        String token = request.authenticated() ? tokens.obtenerToken() : null;
        HttpResponse<String> response = enviar(request, token);
        if (response.statusCode() == 401 && request.authenticated()) {
            response = enviar(request, tokens.renovarTrasRechazo(token));
        }
        return leerCuerpo(response);
    }

    private String leerCuerpo(HttpResponse<String> response) {
        if (response.statusCode() >= 300) {
            throw problems.mapear(response.statusCode(), response.body());
        }
        return response.body();
    }

    private HttpResponse<String> enviar(RestRequest request, String token) {
        try {
            return http.send(construir(request, token), HttpResponse.BodyHandlers.ofString());
        } catch (SSLException e) {
            throw new UnavailableException("TLS_REJECTED",
                    "No se pudo validar el certificado del servidor con la CA configurada", e);
        } catch (IOException e) {
            throw new UnavailableException(RetryPolicy.SERVER_UNREACHABLE, "No se pudo contactar al servidor", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("INTERRUPTED", "Operación interrumpida", e);
        }
    }

    private HttpRequest construir(RestRequest request, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + request.path()))
                .timeout(request.timeout())
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (request.body() == null) {
            return builder.method(request.method(), HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header("Content-Type", "application/json")
                .method(request.method(), HttpRequest.BodyPublishers.ofString(serializar(request.body())))
                .build();
    }

    private String serializar(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("No se pudo serializar la petición", e);
        }
    }

    private <T> T convertir(String body, JavaType type) {
        try {
            return json.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new UnavailableException("INVALID_RESPONSE", "El servidor devolvió una respuesta inválida", e);
        }
    }
}
