package co.edu.upb.dfsha.server.auth.application;

import co.edu.upb.dfsha.server.audit.application.AuditService;
import co.edu.upb.dfsha.server.auth.domain.*;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import java.security.*;
import java.time.Clock;
import java.util.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final MetadataStore store;
  private final TokenIssuer tokens;
  private final LoginThrottle throttle;
  private final Clock clock;
  private final SecureRandom random;
  private final AuditService audit;
  private final Argon2PasswordEncoder encoder =
      Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  private final String dummy = encoder.encode("usuario-inexistente-aleatorio");

  public record LoginResult(String accessToken, String refreshToken, long expiresIn) {}

  public AuthService(
      MetadataStore store,
      TokenIssuer tokens,
      LoginThrottle throttle,
      Clock clock,
      SecureRandom random,
      AuditService audit) {
    this.store = store;
    this.tokens = tokens;
    this.throttle = throttle;
    this.clock = clock;
    this.random = random;
    this.audit = audit;
  }

  public String proteger(String password) {
    DfsException.exigir(
        password != null && password.length() >= 12,
        400,
        "INVALID_REQUEST",
        "La contrasenia requiere 12 caracteres");
    return encoder.encode(password);
  }

  public LoginResult ingresar(String username, String password, String ip) {
    DfsException.exigir(
        username != null && password != null, 400, "INVALID_REQUEST", "Credenciales requeridas");
    try {
      throttle.verificar(username, ip);
    } catch (DfsException ex) {
      store.escribir(
          "audit-entry",
          state -> {
            audit.registrar(state, username, "LOGIN", ex.obtenerCodigo());
            return null;
          });
      throw ex;
    }
    User user = store.leer(s -> s.users.get(username));
    boolean valid = encoder.matches(password, user == null ? dummy : user.passwordHash());
    if (user == null || !valid) {
      throttle.registrarFallo(username, ip);
      store.escribir(
          "refresh-tokens",
          s -> {
            audit.registrar(s, username, "LOGIN", "INVALID_CREDENTIALS");
            return null;
          });
      throw new DfsException(401, "INVALID_CREDENTIALS", "Credenciales invalidas");
    }
    return store.escribir(
        "refresh-tokens",
        s -> {
          audit.registrar(s, username, "LOGIN", "OK");
          return emitir(s, user);
        });
  }

  public LoginResult refrescar(String refresh) {
    String hash = calcularHash(refresh);
    return store.escribir(
        "refresh-tokens",
        s -> {
          RefreshSession session = s.refreshTokens.get(hash);
          DfsException.exigir(
              session != null && session.expiresAt().isAfter(clock.instant()),
              401,
              "INVALID_TOKEN",
              "Refresh invalido");
          s.refreshTokens.remove(hash);
          return emitir(s, s.users.get(session.username()));
        });
  }

  public void salir(String refresh, String user) {
    String hash = calcularHash(refresh);
    store.escribir(
        "refresh-tokens",
        s -> {
          var session = s.refreshTokens.get(hash);
          DfsException.exigir(
              session != null && session.username().equals(user),
              401,
              "INVALID_TOKEN",
              "Refresh invalido");
          s.refreshTokens.remove(hash);
          audit.registrar(s, user, "LOGOUT", "OK");
          return null;
        });
  }

  private LoginResult emitir(
      co.edu.upb.dfsha.server.journal.domain.MetadataState state, User user) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String hash = calcularHash(refresh);
    state.refreshTokens.put(
        hash, new RefreshSession(hash, user.username(), clock.instant().plusSeconds(28800)));
    return new LoginResult(tokens.emitirAcceso(user.username(), user.role()), refresh, 900);
  }

  public static String calcularHash(String input) {
    DfsException.exigir(input != null, 401, "INVALID_TOKEN", "Token requerido");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
