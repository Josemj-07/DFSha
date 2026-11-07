package co.edu.upb.dfsha.server.files.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.edu.upb.dfsha.server.auth.application.*;
import co.edu.upb.dfsha.server.auth.domain.User;
import co.edu.upb.dfsha.server.blocks.application.PowerOfTwoChoicesPolicy;
import co.edu.upb.dfsha.server.cluster.application.ClusterState;
import co.edu.upb.dfsha.server.cluster.domain.Node;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.config.DfsProperties;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.locks.application.LockManager;
import co.edu.upb.dfsha.server.locks.domain.BlockRange;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import co.edu.upb.dfsha.server.support.*;
import java.util.*;
import org.junit.jupiter.api.*;

class AllocationServiceTest {
  final MutableClock clock = new MutableClock();
  final MemoryStore store = new MemoryStore(clock);
  final ClusterState cluster = new ClusterState();
  final CurrentUser current = mock(CurrentUser.class);
  AllocationService service;

  @BeforeEach
  void preparar() {
    when(current.obtenerNombre()).thenReturn("ana");
    var locks = new LockManager(store, clock);
    for (String id : List.of("a", "b", "c"))
      cluster.registrar(
          new Node(id, id, 50051, 50052, id, 100, 0, 0, clock.instant(), "ALIVE", null, true));
    store.mutar(
        s -> {
          s.users.put("ana", new User("ana", "hash", "USER", 2));
          s.inodes.put(
              1L,
              new Inode(
                  1,
                  "ana",
                  "rw-------",
                  false,
                  0,
                  0,
                  null,
                  clock.instant(),
                  clock.instant(),
                  List.of()));
          co.edu.upb.dfsha.server.support.TestNamespace.crearHome(s, "ana", clock);
          s.directories.poner("/home/ana/f", 1L);
          var held = locks.conceder(s, 1, "ana", "EXCLUSIVE", BlockRange.completo(), 30000);
          s.handles.put(
              "h",
              new Handle(
                  "h",
                  "ana",
                  1,
                  "w",
                  0,
                  List.of(),
                  held.id(),
                  clock.instant().plusSeconds(30),
                  Map.of(),
                  null,
                  null));
          return null;
        });
    service =
        new AllocationService(
            store,
            new HandleAccess(clock, current),
            new PowerOfTwoChoicesPolicy(new java.security.SecureRandom()),
            cluster,
            mock(TokenIssuer.class),
            locks,
            clock,
            new DfsProperties(null, null, 4, "password"));
  }

  @Test
  void permitirUltimoBloqueParcialConCuotaMenorAlBloque() {
    assertEquals(1, service.asignar("h", 0, 1).blocks().size());
  }

  @Test
  void rechazarTamanoMinimoMayorALaCuota() {
    assertEquals(
        "QUOTA_EXCEEDED",
        assertThrows(DfsException.class, () -> service.asignar("h", 0, 2)).obtenerCodigo());
  }

  @Test
  void recuperarConGeneracionNuevaYExcluirNodoFallido() {
    var allocated = service.asignar("h", 0, 1).blocks().getFirst();
    String failed = allocated.pipeline().getFirst().nodeId();
    var recovered =
        service.recuperarBloque("h", new RecoverRequest(0, List.of(failed))).blocks().getFirst();
    assertEquals(allocated.blockId(), recovered.blockId());
    assertEquals(2, recovered.gen());
    assertTrue(recovered.pipeline().stream().noneMatch(n -> n.nodeId().equals(failed)));
  }
}
