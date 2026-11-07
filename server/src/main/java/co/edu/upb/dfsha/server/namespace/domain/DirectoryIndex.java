package co.edu.upb.dfsha.server.namespace.domain;

import java.util.*;

public final class DirectoryIndex {
  public Map<DirectoryEntry, Long> entries = new HashMap<>();

  public Long obtener(String path) {
    if (path == null || !path.startsWith("/")) return null;
    Long current = entries.get(new DirectoryEntry(0, ""));
    if (path.equals("/")) return current;
    for (String name : path.substring(1).split("/")) {
      if (current == null) return null;
      current = entries.get(new DirectoryEntry(current, name));
    }
    return current;
  }

  public boolean contenerRuta(String path) {
    return obtener(path) != null;
  }

  public boolean contenerInodo(long inode) {
    return entries.containsValue(inode);
  }

  public Collection<Long> obtenerInodos() {
    return List.copyOf(entries.values());
  }

  public void poner(String path, long inode) {
    entries.put(obtenerClave(path), inode);
  }

  public Long eliminar(String path) {
    return entries.remove(obtenerClave(path));
  }

  public void mover(String from, String to) {
    if (from.equals("/")) throw new IllegalArgumentException("No se puede mover la raiz");
    DirectoryEntry source = obtenerClave(from), target = obtenerClave(to);
    Long id = entries.remove(source);
    if (id == null) throw new IllegalArgumentException("Origen inexistente");
    entries.put(target, id);
  }

  public Set<String> listarRutas() {
    Set<String> paths = new TreeSet<>();
    Long root = obtener("/");
    Map<Long, List<Map.Entry<DirectoryEntry, Long>>> children = new HashMap<>();
    entries
        .entrySet()
        .forEach(
            entry ->
                children
                    .computeIfAbsent(entry.getKey().parentInode(), id -> new ArrayList<>())
                    .add(entry));
    if (root != null) recorrer(root, "/", paths, new HashSet<>(), children);
    return paths;
  }

  private DirectoryEntry obtenerClave(String path) {
    if (path.equals("/")) return new DirectoryEntry(0, "");
    Long parent = obtener(PathNormalizer.obtenerPadre(path));
    if (parent == null) throw new IllegalArgumentException("Directorio padre inexistente");
    return new DirectoryEntry(parent, path.substring(path.lastIndexOf('/') + 1));
  }

  private void recorrer(
      long inode,
      String path,
      Set<String> paths,
      Set<Long> ancestors,
      Map<Long, List<Map.Entry<DirectoryEntry, Long>>> children) {
    if (!ancestors.add(inode)) throw new IllegalStateException("Ciclo en directorios");
    paths.add(path);
    for (var entry : children.getOrDefault(inode, List.of())) {
      String child =
          path.equals("/") ? "/" + entry.getKey().name() : path + "/" + entry.getKey().name();
      recorrer(entry.getValue(), child, paths, ancestors, children);
    }
    ancestors.remove(inode);
  }

  public DirectoryIndex copiar() {
    var result = new DirectoryIndex();
    result.entries.putAll(entries);
    return result;
  }
}
