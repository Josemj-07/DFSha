package co.edu.upb.dfsha.client.config;

import java.util.ArrayDeque;
import java.util.Deque;

/** Convierte rutas remotas relativas en absolutas y normaliza {@code .}, {@code ..} y barras repetidas. */
public final class PathResolver {

    private PathResolver() {
    }

    public static String resolver(String cwd, String path) {
        String joined = path.startsWith("/") ? path : cwd + "/" + path;
        Deque<String> parts = new ArrayDeque<>();
        for (String part : joined.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(part);
            }
        }
        return "/" + String.join("/", parts);
    }

    public static String obtenerNombre(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
