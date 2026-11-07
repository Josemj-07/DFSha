package co.edu.upb.dfsha.server.admin.application;

import co.edu.upb.dfsha.server.audit.application.AuditService;
import co.edu.upb.dfsha.server.auth.application.*;
import co.edu.upb.dfsha.server.auth.domain.User;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.config.DfsProperties;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdminService {
  private final MetadataStore store;
  private final AuthService auth;
  private final DfsProperties props;
  private final AuditService audit;
  private final Clock clock;

  public AdminService(
      MetadataStore store, AuthService auth, DfsProperties props, AuditService audit, Clock clock) {
    this.store = store;
    this.auth = auth;
    this.props = props;
    this.audit = audit;
    this.clock = clock;
  }

  @PostConstruct
  public void inicializar() {
    if (store.leer(s -> s.users.containsKey("admin"))) return;
    String hash = auth.proteger(props.adminPassword());
    store.escribir(
        "create-user",
        s -> {
          crearDirectorio(s, "/", "admin");
          crearDirectorio(s, "/home", "admin");
          crearUsuario(s, "admin", hash, "ADMIN");
          return null;
        });
  }

  public void crear(String username, String password, String role, String actor) {
    DfsException.exigir(
        username != null
            && username.matches("[a-zA-Z0-9_-]{1,64}")
            && role != null
            && Set.of("USER", "ADMIN").contains(role),
        400,
        "INVALID_REQUEST",
        "Usuario o rol invalido");
    String hash = auth.proteger(password);
    store.escribir(
        "create-user",
        s -> {
          DfsException.exigir(
              !s.users.containsKey(username), 409, "ALREADY_EXISTS", "Usuario existente");
          crearUsuario(s, username, hash, role);
          audit.registrar(s, actor, "CREATE_USER", "OK");
          return null;
        });
  }

  public void actualizarCuota(String username, long bytes, String actor) {
    DfsException.exigir(bytes >= 0, 400, "INVALID_REQUEST", "Cuota negativa");
    store.escribir(
        "update-quota",
        s -> {
          User user = s.users.get(username);
          DfsException.exigir(user != null, 404, "NOT_FOUND", "Usuario inexistente");
          s.users.put(username, new User(username, user.passwordHash(), user.role(), bytes));
          audit.registrar(s, actor, "UPDATE_QUOTA", "OK");
          return null;
        });
  }

  private void crearUsuario(MetadataState s, String name, String hash, String role) {
    s.users.put(name, new User(name, hash, role, Long.MAX_VALUE));
    crearDirectorio(s, "/home/" + name, name);
  }

  private void crearDirectorio(MetadataState s, String path, String owner) {
    long id = ++s.inodeSequence;
    s.directories.poner(path, id);
    s.inodes.put(
        id,
        new Inode(
            id, owner, "rwx------", true, 0, 0, null, clock.instant(), clock.instant(), List.of()));
  }
}
