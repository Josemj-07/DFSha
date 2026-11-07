package co.edu.upb.dfsha.server.audit.application;

import co.edu.upb.dfsha.server.audit.domain.AuditEntry;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;

@Service
public class AuditService {
  private final Clock clock;

  public AuditService(Clock clock) {
    this.clock = clock;
  }

  public void registrar(MetadataState state, String user, String action, String result) {
    var attrs = RequestContextHolder.getRequestAttributes();
    String ip = "internal", trace = "internal";
    if (attrs instanceof ServletRequestAttributes servlet) {
      ip = servlet.getRequest().getRemoteAddr();
      trace = String.valueOf(servlet.getRequest().getAttribute("traceId"));
    }
    state.audit.add(new AuditEntry(clock.instant(), user, action, ip, result, trace));
  }
}
