package co.edu.upb.dfsha.server.config;

import co.edu.upb.dfsha.server.auth.infra.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService usuariosJwt() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Autenticacion por JWT");
    };
  }

  @Bean
  SecurityFilterChain seguridad(HttpSecurity http, TokenService tokens) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/v1/auth/login", "/v1/auth/refresh", "/actuator/health")
                    .permitAll()
                    .requestMatchers("/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(
            new JwtAuthenticationFilter(tokens), UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) ->
                            responder(res, 401, "UNAUTHENTICATED", req.getAttribute("traceId")))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            responder(res, 403, "FORBIDDEN", req.getAttribute("traceId"))))
        .build();
  }

  private void responder(
      jakarta.servlet.http.HttpServletResponse res, int status, String code, Object trace)
      throws java.io.IOException {
    res.setStatus(status);
    res.setContentType("application/problem+json");
    res.getWriter()
        .write(
            "{\"type\":\"/errors/"
                + code.toLowerCase()
                + "\",\"title\":\""
                + code
                + "\",\"status\":"
                + status
                + ",\"code\":\""
                + code
                + "\",\"traceId\":\""
                + trace
                + "\"}");
  }
}
