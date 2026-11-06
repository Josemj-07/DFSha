package co.edu.upb.dfsha.client.metadata;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Codifica valores para rutas y parámetros de consulta. */
final class QueryString {

    private QueryString() {
    }

    static String codificar(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
