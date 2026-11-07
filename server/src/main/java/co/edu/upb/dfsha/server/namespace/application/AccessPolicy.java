package co.edu.upb.dfsha.server.namespace.application;

import co.edu.upb.dfsha.server.auth.application.CurrentUser;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.namespace.domain.*;
import org.springframework.stereotype.Component;

@Component
public class AccessPolicy {
  private final CurrentUser current;

  public AccessPolicy(CurrentUser current) {
    this.current = current;
  }

  public String verificar(String path) {
    String normalized = PathNormalizer.normalizar(path), home = "/home/" + current.obtenerNombre();
    DfsException.exigir(
        current.esAdministrador() || normalized.equals(home) || normalized.startsWith(home + "/"),
        403,
        "FORBIDDEN",
        "Ruta fuera del espacio del usuario");
    return normalized;
  }

  public void verificar(Inode inode, boolean write) {
    boolean admin = current.esAdministrador();
    String user = current.obtenerNombre();
    DfsException.exigir(
        admin || inode.owner().equals(user), 403, "FORBIDDEN", "Propietario distinto");
    DfsException.exigir(
        admin || inode.mode().charAt(write ? 1 : 0) == (write ? 'w' : 'r'),
        403,
        "FORBIDDEN",
        "Permisos insuficientes");
  }
}
