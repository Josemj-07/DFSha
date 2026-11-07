package co.edu.upb.dfsha.server.namespace.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.edu.upb.dfsha.server.audit.application.AuditService;
import co.edu.upb.dfsha.server.auth.application.CurrentUser;
import co.edu.upb.dfsha.server.auth.domain.User;
import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import co.edu.upb.dfsha.server.support.*;
import java.util.*;
import org.junit.jupiter.api.*;

class NamespaceServiceTest {
  final MutableClock clock = new MutableClock();
  final MemoryStore store = new MemoryStore(clock);
  final CurrentUser user = mock(CurrentUser.class);
  NamespaceService service;

  @BeforeEach
  void preparar() {
    when(user.obtenerNombre()).thenReturn("ana");
    store.mutar(
        state -> {
          TestNamespace.crearHome(state, "ana", clock);
          state.users.put("ana", new User("ana", "hash", "USER", 1000));
          return null;
        });
    service =
        new NamespaceService(store, new AccessPolicy(user), user, clock, new AuditService(clock));
  }

  private void crearArchivo() {
    store.mutar(
        state -> {
          state.directories.poner("/home/ana/f", 1);
          state.inodes.put(
              1L,
              new Inode(
                  1,
                  "ana",
                  "rw-------",
                  false,
                  4,
                  1,
                  "hash",
                  clock.instant(),
                  clock.instant(),
                  List.of(new Block(0, "blk", 1, 4, "a".repeat(64)))));
          return null;
        });
  }

  @Test
  void copiarSinDuplicarBloquesYActualizarUso() {
    crearArchivo();
    service.moverOCopiar("/home/ana/f", "/home/ana/copy", true);
    store.leer(
        state -> {
          assertEquals(2, state.blocks.get("blk:1").refCount());
          assertEquals(8, state.users.get("ana").usageBytes());
          return null;
        });
    service.eliminar("/home/ana/f", false, false);
    store.leer(
        state -> {
          assertEquals(1, state.blocks.get("blk:1").refCount());
          assertEquals(4, state.users.get("ana").usageBytes());
          return null;
        });
    service.eliminar("/home/ana/copy", false, false);
    store.leer(
        state -> {
          assertEquals(0, state.blocks.get("blk:1").refCount());
          assertEquals(0, state.users.get("ana").usageBytes());
          assertEquals(clock.instant(), state.unreferenced.get("blk:1"));
          return null;
        });
  }

  @Test
  void moverDirectorioConservarInodosHijos() {
    service.crearDirectorio("/home/ana/a/b", true);
    long id = store.leer(state -> state.directories.obtener("/home/ana/a/b"));
    service.moverOCopiar("/home/ana/a", "/home/ana/renamed", false);
    assertEquals(
        id,
        store.<Long>leer(state -> state.directories.obtener("/home/ana/renamed/b")).longValue());
    assertThrows(
        co.edu.upb.dfsha.server.common.DfsException.class,
        () -> service.consultar("/home/ana/a/b"));
  }

  @Test
  void borrarArbolDesdeLosHijos() {
    service.crearDirectorio("/home/ana/a/b/c", true);
    service.eliminar("/home/ana/a", true, true);
    assertTrue(
        store.<Boolean>leer(
            state ->
                state.directories.listarRutas().stream()
                    .noneMatch(path -> path.startsWith("/home/ana/a"))));
  }

  @Test
  void paginarSinRepetirNombres() {
    store.mutar(
        state -> {
          for (int i = 0; i < 101; i++) {
            String path = "/home/ana/f" + String.format("%03d", i);
            long id = ++state.inodeSequence;
            state.directories.poner(path, id);
            state.inodes.put(
                id,
                new Inode(
                    id,
                    "ana",
                    "rw-------",
                    false,
                    0,
                    0,
                    null,
                    clock.instant(),
                    clock.instant(),
                    List.of()));
          }
          return null;
        });
    var first = service.listar("/home/ana", null);
    var last = service.listar("/home/ana", first.nextCursor());
    assertEquals(100, first.entries().size());
    assertEquals("f099", first.nextCursor());
    assertEquals(1, last.entries().size());
    assertEquals("f100", last.entries().getFirst().name());
    assertNull(last.nextCursor());
  }
}
