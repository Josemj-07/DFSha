package co.edu.upb.dfsha.server.auth.infra;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final TokenService tokens;

  public JwtAuthenticationFilter(TokenService tokens) {
    this.tokens = tokens;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String header = req.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer "))
      try {
        var claims = tokens.verificar(header.substring(7));
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    claims.getSubject(),
                    null,
                    List.of(
                        new SimpleGrantedAuthority("ROLE_" + claims.get("role", String.class)))));
      } catch (io.jsonwebtoken.JwtException | IllegalArgumentException ex) {
        SecurityContextHolder.clearContext();
      }
    chain.doFilter(req, res);
  }
}
