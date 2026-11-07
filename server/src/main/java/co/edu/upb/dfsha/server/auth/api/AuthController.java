package co.edu.upb.dfsha.server.auth.api;

import co.edu.upb.dfsha.server.auth.application.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
  private final AuthService service;
  private final CurrentUser current;

  public AuthController(AuthService service, CurrentUser current) {
    this.service = service;
    this.current = current;
  }

  public record Credentials(String username, String password) {}

  public record Refresh(String refreshToken) {}

  @PostMapping("/login")
  public AuthService.LoginResult ingresar(@RequestBody Credentials body, HttpServletRequest req) {
    return service.ingresar(body.username(), body.password(), req.getRemoteAddr());
  }

  @PostMapping("/refresh")
  public AuthService.LoginResult refrescar(@RequestBody Refresh body) {
    return service.refrescar(body.refreshToken());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void salir(@RequestBody Refresh body) {
    service.salir(body.refreshToken(), current.obtenerNombre());
  }
}
