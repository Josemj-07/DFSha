package co.edu.upb.dfsha.server.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    req.setAttribute("traceId", id);
    res.setHeader("X-Trace-Id", id);
    try (var scope = MDC.putCloseable("traceId", id)) {
      chain.doFilter(req, res);
    }
  }
}
