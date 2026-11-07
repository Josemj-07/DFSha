package co.edu.upb.dfsha.server.cluster.infra;

import co.edu.upb.dfsha.server.cluster.application.ClusterState;
import co.edu.upb.dfsha.server.common.DfsException;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Component
public class SafeModeInterceptor implements HandlerInterceptor, WebMvcConfigurer {
  private final ClusterState cluster;

  public SafeModeInterceptor(ClusterState cluster) {
    this.cluster = cluster;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(this)
        .addPathPatterns("/v1/files**", "/v1/files/**", "/v1/locks", "/v1/locks/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    DfsException.exigir(
        !cluster.estaSeguro(), 503, "SAFE_MODE", "Esperando reportes de al menos dos nodos");
    return true;
  }
}
