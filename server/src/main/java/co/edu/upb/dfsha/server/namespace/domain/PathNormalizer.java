package co.edu.upb.dfsha.server.namespace.domain;

import java.util.ArrayDeque;

public final class PathNormalizer {
  private PathNormalizer() {}

  public static String normalizar(String path) {
    if (path == null || !path.startsWith("/") || path.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Ruta absoluta requerida");
    var parts = new ArrayDeque<String>();
    if (path.equals("/")) return "/";
    for (String part : path.substring(1).split("/", -1)) {
      if (part.isEmpty()) throw new IllegalArgumentException("Nombre vacio");
      if (part.equals(".")) continue;
      if (part.equals("..")) {
        if (parts.isEmpty()) throw new IllegalArgumentException("Ruta fuera de raiz");
        parts.removeLast();
      } else parts.add(part);
    }
    return "/" + String.join("/", parts);
  }

  public static String obtenerPadre(String path) {
    int i = path.lastIndexOf('/');
    return i == 0 ? "/" : path.substring(0, i);
  }
}
