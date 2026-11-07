package co.edu.upb.dfsha.server.namespace.application;

import co.edu.upb.dfsha.server.audit.application.AuditService;
import co.edu.upb.dfsha.server.auth.application.CurrentUser;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import co.edu.upb.dfsha.server.namespace.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class NamespaceService {
  private final MetadataStore store;
  private final AccessPolicy policy;
  private final CurrentUser current;
  private final Clock clock;
  private final AuditService audit;

  public record Attributes(
      String name,
      String path,
      String type,
      String owner,
      String mode,
      long size,
      long version,
      Instant mtime) {}

  public record Page(List<Attributes> entries, String nextCursor) {}

  public NamespaceService(
      MetadataStore store,
      AccessPolicy policy,
      CurrentUser current,
      Clock clock,
      AuditService audit) {
    this.store = store;
    this.policy = policy;
    this.current = current;
    this.clock = clock;
    this.audit = audit;
  }

  public static Inode obtener(MetadataState state, String path) {
    Long id = state.directories.obtener(path);
    DfsException.exigir(id != null, 404, "NOT_FOUND", "Ruta inexistente");
    return state.inodes.get(id);
  }

  public Attributes consultar(String raw) {
    String path = policy.verificar(raw);
    return store.leer(
        s -> {
          var inode = obtener(s, path);
          policy.verificar(inode, false);
          return describir(path, inode);
        });
  }

  public Page listar(String raw, String cursor) {
    String path = policy.verificar(raw);
    return store.leer(
        s -> {
          Inode inode = obtener(s, path);
          policy.verificar(inode, false);
          DfsException.exigir(inode.directory(), 409, "NOT_DIRECTORY", "Se requiere directorio");
          String prefix = path.equals("/") ? "/" : path + "/";
          var paths =
              s.directories.listarRutas().stream()
                  .filter(
                      p ->
                          p.startsWith(prefix)
                              && !p.equals(path)
                              && !p.substring(prefix.length()).contains("/"))
                  .filter(p -> cursor == null || p.substring(prefix.length()).compareTo(cursor) > 0)
                  .sorted()
                  .limit(101)
                  .toList();
          var entries =
              paths.stream()
                  .limit(100)
                  .map(p -> describir(p, s.inodes.get(s.directories.obtener(p))))
                  .toList();
          return new Page(entries, paths.size() > 100 ? entries.getLast().name() : null);
        });
  }

  public void crearDirectorio(String raw, boolean parents) {
    String path = policy.verificar(raw);
    store.escribir(
        "create-dir",
        s -> {
          DfsException.exigir(
              !s.directories.contenerRuta(path), 409, "ALREADY_EXISTS", "Ruta existente");
          crearPadres(s, path, parents);
          audit.registrar(s, current.obtenerNombre(), "MKDIR", "OK");
          return null;
        });
  }

  private void crearPadres(MetadataState s, String path, boolean parents) {
    String parent = PathNormalizer.obtenerPadre(path);
    if (!s.directories.contenerRuta(parent) && parents) crearPadres(s, parent, true);
    Inode directory = obtener(s, parent);
    DfsException.exigir(directory.directory(), 409, "NOT_DIRECTORY", "Padre no es directorio");
    policy.verificar(directory, true);
    long id = ++s.inodeSequence;
    Instant now = clock.instant();
    s.directories.poner(path, id);
    s.inodes.put(
        id,
        new Inode(id, current.obtenerNombre(), "rwx------", true, 0, 0, null, now, now, List.of()));
  }

  public void eliminar(String raw, boolean directory, boolean recursive) {
    String path = policy.verificar(raw);
    store.escribir(
        directory ? "delete-dir" : "delete-file",
        s -> {
          Inode inode = obtener(s, path);
          policy.verificar(inode, true);
          DfsException.exigir(
              inode.directory() == directory, 409, "INVALID_OPERATION", "Tipo incompatible");
          var children =
              s.directories.listarRutas().stream().filter(p -> p.startsWith(path + "/")).toList();
          DfsException.exigir(
              !directory || recursive || children.isEmpty(),
              409,
              "DIR_NOT_EMPTY",
              "Directorio no vacio");
          DfsException.exigir(
              !path.equals("/")
                  && !path.equals("/home")
                  && !path.equals("/home/" + current.obtenerNombre()),
              403,
              "FORBIDDEN",
              "Directorio reservado");
          var paths = new ArrayList<>(children);
          paths.add(path);
          paths.sort(Comparator.comparingInt(String::length).reversed());
          for (String item : paths) {
            long id = s.directories.eliminar(item);
            Inode removed = s.inodes.get(id);
            removed
                .blocks()
                .forEach(
                    b -> s.unreferenced.putIfAbsent(b.blockId() + ":" + b.gen(), clock.instant()));
            if (s.handles.values().stream().noneMatch(h -> h.inodeId() == id)) s.inodes.remove(id);
          }
          audit.registrar(s, current.obtenerNombre(), directory ? "RMDIR" : "RM", "OK");
          return null;
        });
  }

  public void moverOCopiar(String source, String target, boolean copy) {
    String from = policy.verificar(source), to = policy.verificar(target);
    store.escribir(
        copy ? "copy-file" : "move",
        s -> {
          Inode inode = obtener(s, from);
          policy.verificar(inode, !copy);
          DfsException.exigir(
              !s.directories.contenerRuta(to), 409, "ALREADY_EXISTS", "Destino existente");
          DfsException.exigir(
              !to.startsWith(from + "/"), 409, "INVALID_OPERATION", "Destino dentro del origen");
          Inode parent = obtener(s, PathNormalizer.obtenerPadre(to));
          policy.verificar(parent, true);
          DfsException.exigir(parent.directory(), 409, "NOT_DIRECTORY", "Padre no es directorio");
          if (copy) {
            DfsException.exigir(
                !inode.directory(), 409, "INVALID_OPERATION", "Solo se copian archivos");
            verificarCuota(s, inode.owner(), inode.size());
            long id = ++s.inodeSequence;
            s.inodes.put(
                id,
                new Inode(
                    id,
                    inode.owner(),
                    inode.mode(),
                    false,
                    inode.size(),
                    inode.version(),
                    inode.fileSha256(),
                    clock.instant(),
                    clock.instant(),
                    inode.blocks()));
            s.directories.poner(to, id);
          } else {
            s.directories.mover(from, to);
          }
          audit.registrar(s, current.obtenerNombre(), copy ? "CP" : "MV", "OK");
          return null;
        });
  }

  public static void verificarCuota(MetadataState s, String owner, long extra) {
    long usage = s.users.get(owner).usageBytes();
    DfsException.exigir(
        extra <= s.users.get(owner).quotaBytes() - usage, 507, "QUOTA_EXCEEDED", "Cuota excedida");
  }

  private Attributes describir(String path, Inode inode) {
    return new Attributes(
        path.substring(path.lastIndexOf('/') + 1),
        path,
        inode.directory() ? "DIR" : "FILE",
        inode.owner(),
        inode.mode(),
        inode.size(),
        inode.version(),
        inode.mtime());
  }
}
