package co.edu.upb.dfsha.server.auth.application;

import co.edu.upb.dfsha.server.common.DfsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
  public String obtenerNombre() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    DfsException.exigir(
        auth != null && auth.isAuthenticated(), 401, "UNAUTHENTICATED", "Sesion requerida");
    return auth.getName();
  }

  public boolean esAdministrador() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null
        && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
  }
}
