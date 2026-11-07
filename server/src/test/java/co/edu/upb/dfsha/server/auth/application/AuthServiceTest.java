package co.edu.upb.dfsha.server.auth.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.edu.upb.dfsha.server.audit.application.AuditService;
import co.edu.upb.dfsha.server.auth.domain.User;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.support.*;
import org.junit.jupiter.api.*;

class AuthServiceTest {
  final MutableClock clock = new MutableClock();
  final MemoryStore store = new MemoryStore(clock);
  final TokenIssuer tokens = mock(TokenIssuer.class);
  final AuthService service =
      new AuthService(
          store,
          tokens,
          new LoginThrottle(clock),
          clock,
          new java.security.SecureRandom(),
          new AuditService(clock));

  @BeforeEach
  void preparar() {
    store.mutar(
        s -> {
          s.users.put("ana", new User("ana", service.proteger("contrasenia-segura"), "USER", 100));
          return null;
        });
    when(tokens.emitirAcceso("ana", "USER")).thenReturn("access");
  }

  @Test
  void rotarYRechazarRefreshReutilizado() {
    var login = service.ingresar("ana", "contrasenia-segura", "ip");
    var next = service.refrescar(login.refreshToken());
    assertNotEquals(login.refreshToken(), next.refreshToken());
    assertThrows(DfsException.class, () -> service.refrescar(login.refreshToken()));
    assertEquals(1, store.leer(s -> s.refreshTokens.size()).intValue());
  }

  @Test
  void revocarLogout() {
    var login = service.ingresar("ana", "contrasenia-segura", "ip");
    service.salir(login.refreshToken(), "ana");
    assertThrows(DfsException.class, () -> service.refrescar(login.refreshToken()));
  }

  @Test
  void vencerRefresh() {
    var login = service.ingresar("ana", "contrasenia-segura", "ip");
    clock.avanzar(28801);
    assertThrows(DfsException.class, () -> service.refrescar(login.refreshToken()));
  }

  @Test
  void limitarPorCuenta() {
    for (int i = 0; i < 5; i++)
      assertEquals(
          "INVALID_CREDENTIALS",
          assertThrows(DfsException.class, () -> service.ingresar("ana", "mal", "ip"))
              .obtenerCodigo());
    assertEquals(
        "TOO_MANY_ATTEMPTS",
        assertThrows(DfsException.class, () -> service.ingresar("ana", "mal", "ip"))
            .obtenerCodigo());
    clock.avanzar(901);
    assertDoesNotThrow(() -> service.ingresar("ana", "contrasenia-segura", "ip"));
  }

  @Test
  void limitarPorIp() {
    for (int i = 0; i < 10; i++) {
      String user = "inexistente" + i;
      assertThrows(DfsException.class, () -> service.ingresar(user, "mal", "ip"));
    }
    assertEquals(
        "TOO_MANY_ATTEMPTS",
        assertThrows(DfsException.class, () -> service.ingresar("ana", "contrasenia-segura", "ip"))
            .obtenerCodigo());
  }

  @Test
  void rechazarPasswordCorta() {
    assertThrows(DfsException.class, () -> service.proteger("corta"));
  }

  @Test
  void auditarIntentoBloqueadoPorLimite() {
    for (int attempt = 0; attempt < 6; attempt++)
      assertThrows(DfsException.class, () -> service.ingresar("ana", "incorrecta", "ip"));
    var entries = store.leer(state -> state.audit);
    assertEquals(6, entries.size());
    assertEquals("TOO_MANY_ATTEMPTS", entries.getLast().result());
  }
}
