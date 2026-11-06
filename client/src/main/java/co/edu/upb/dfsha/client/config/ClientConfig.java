package co.edu.upb.dfsha.client.config;

import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Configuración del cliente: URL del servidor, certificado de la CA y directorio local. */
public record ClientConfig(String serverUrl, Path caCert, Path homeDir) {

    private static final String CONFIG_FILE = "config.properties";

    /** Prioridad: opciones, luego variables de entorno, luego {@code ~/.dfs/config.properties}. */
    public static ClientConfig cargar(String serverOption, String caOption, Map<String, String> env, String userHome) {
        Path home = Path.of(env.getOrDefault("DFS_HOME", Path.of(userHome, ".dfs").toString()));
        Properties file = leerArchivo(home.resolve(CONFIG_FILE));
        String server = primero(serverOption, env.get("DFS_SERVER_URL"), file.getProperty("server.url"));
        String ca = primero(caOption, env.get("DFS_CA_CERT"), file.getProperty("ca.cert"));
        if (server == null) {
            throw new InvalidOperationException("MISSING_CONFIG",
                    "Falta la URL del servidor: usa --server o la variable DFS_SERVER_URL");
        }
        if (ca == null) {
            throw new InvalidOperationException("MISSING_CONFIG",
                    "Falta el certificado de la CA: usa --ca-cert o la variable DFS_CA_CERT");
        }
        return new ClientConfig(validarUrl(server), Path.of(ca), home);
    }

    private static String validarUrl(String url) {
        if (!url.startsWith("https://")) {
            throw new InvalidOperationException("INSECURE_URL", "La URL del servidor debe empezar por https://");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String primero(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Properties leerArchivo(Path file) {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo leer " + file, e);
            }
        }
        return properties;
    }
}
