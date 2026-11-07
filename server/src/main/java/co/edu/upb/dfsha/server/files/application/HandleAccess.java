package co.edu.upb.dfsha.server.files.application;

import co.edu.upb.dfsha.server.auth.application.CurrentUser;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public class HandleAccess {
  private final Clock clock;
  private final CurrentUser current;

  public HandleAccess(Clock clock, CurrentUser current) {
    this.clock = clock;
    this.current = current;
  }

  public Handle obtener(MetadataState state, String id) {
    Handle h = state.handles.get(id);
    DfsException.exigir(
        h != null && h.expiresAt().isAfter(clock.instant()),
        410,
        "HANDLE_EXPIRED",
        "Handle vencido");
    DfsException.exigir(
        h.owner().equals(current.obtenerNombre()), 403, "FORBIDDEN", "Handle de otro usuario");
    return h;
  }

  public void verificarEscritura(Handle handle) {
    DfsException.exigir(!handle.mode().equals("r"), 403, "FORBIDDEN", "Handle de lectura");
  }
}
