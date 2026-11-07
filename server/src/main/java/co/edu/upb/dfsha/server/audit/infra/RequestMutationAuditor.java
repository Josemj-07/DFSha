package co.edu.upb.dfsha.server.audit.infra;

import co.edu.upb.dfsha.server.audit.application.AuditService;
import co.edu.upb.dfsha.server.journal.application.MutationAuditor;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

@Component
public class RequestMutationAuditor implements MutationAuditor {
  private final AuditService audit;

  public RequestMutationAuditor(AuditService audit) {
    this.audit = audit;
  }

  @Override
  public void registrar(MetadataState before, MetadataState after) {
    if (after.audit.size() != before.audit.size()) return;
    var attrs = RequestContextHolder.getRequestAttributes();
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (attrs instanceof ServletRequestAttributes servlet
        && auth != null
        && auth.isAuthenticated()) {
      String method = servlet.getRequest().getMethod();
      if (!method.equals("GET"))
        audit.registrar(
            after, auth.getName(), method + " " + servlet.getRequest().getRequestURI(), "OK");
    }
  }
}
