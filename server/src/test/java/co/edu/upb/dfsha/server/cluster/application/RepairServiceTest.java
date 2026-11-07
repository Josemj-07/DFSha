package co.edu.upb.dfsha.server.cluster.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.edu.upb.dfsha.server.auth.application.TokenIssuer;
import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.cluster.domain.*;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import co.edu.upb.dfsha.server.support.*;
import java.util.*;
import org.junit.jupiter.api.*;

class RepairServiceTest {
  final MutableClock clock = new MutableClock();
  final MemoryStore store = new MemoryStore(clock);
  final ClusterState cluster = new ClusterState();
  final CommandGateway commands = mock(CommandGateway.class);
  final BrokerStatus broker = mock(BrokerStatus.class);
  RepairService repair;
  final Replica a = new Replica("a", "blk", 1, 4, "a".repeat(64));
  final Replica b = new Replica("b", "blk", 1, 4, "a".repeat(64));
  final Replica c = new Replica("c", "blk", 1, 4, "a".repeat(64));

  @BeforeEach
  void preparar() {
    when(broker.estaConectado()).thenReturn(true);
    for (String id : List.of("a", "b", "c")) {
      cluster.registrar(
          new Node(id, id, 50051, 50052, id, 100, 0, 0, clock.instant(), "ALIVE", null, false));
      cluster.reportar(id, List.of());
    }
    cluster.agregar(a);
    cluster.agregar(b);
    store.mutar(
        s -> {
          TestNamespace.crearHome(s, "ana", clock);
          s.directories.poner("/home/ana/f", 1L);
          s.inodes.put(
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
                  List.of(new Block(0, "blk", 1, 4, a.sha256()))));
          return null;
        });
    repair = new RepairService(cluster, commands, store, mock(TokenIssuer.class), broker, clock);
  }

  @Test
  void noUsarTiempoDeOtroNodoCaido() {
    cluster.quitar("blk", 1, "b", false);
    cluster.agregar(c);
    clock.avanzar(30);
    cluster.actualizarLatido("a", clock.instant(), 0, 0);
    cluster.actualizarLatido("c", clock.instant(), 0, 0);
    cluster.detectar(clock.instant());
    clock.avanzar(120);
    cluster.actualizarLatido("a", clock.instant(), 0, 0);
    cluster.detectar(clock.instant());
    repair.reparar();
    verifyNoInteractions(commands);
    clock.avanzar(120);
    cluster.actualizarLatido("a", clock.instant(), 0, 0);
    cluster.actualizarLatido("b", clock.instant(), 0, 0);
    cluster.detectar(clock.instant());
    repair.reparar();
    verify(commands)
        .enviar(
            eq("a"),
            argThat(
                command -> command instanceof ReplicateBlock r && r.target().nodeId().equals("b")));
  }

  @Test
  void repararAntesDeBorrarCopiaCorrupta() {
    cluster.quitar("blk", 1, "b", true);
    repair.reparar();
    verify(commands)
        .enviar(
            eq("a"),
            argThat(
                command -> command instanceof ReplicateBlock r && r.target().nodeId().equals("c")));
    verify(commands, never()).enviar(eq("b"), any());
    assertEquals(Set.of("b"), cluster.obtenerCorruptas("blk", 1));
    assertEquals(2, cluster.obtenerInventario().get("blk:1").size());
    cluster.agregar(c);
    repair.reparar();
    verify(commands).enviar(eq("b"), argThat(command -> command instanceof DeleteBlock));
    cluster.quitar("blk", 1, "b", false);
    assertTrue(cluster.obtenerCorruptas("blk", 1).isEmpty());
  }

  @Test
  void reporteNoReviveReplicaCorrupta() {
    cluster.quitar("blk", 1, "b", true);
    cluster.reportar("b", List.of(b));
    assertEquals(List.of(a), cluster.obtenerReplicas("blk", 1, true));
  }

  @Test
  void noDuplicarTrabajoEnCurso() {
    cluster.quitar("blk", 1, "b", true);
    repair.reparar();
    repair.reparar();
    verify(commands, times(1)).enviar(eq("a"), any());
    clock.avanzar(121);
    repair.reparar();
    verify(commands, times(2)).enviar(eq("a"), any());
  }

  @Test
  void conservarDosZonasAlBorrarSobrante() {
    var n = cluster.obtenerNodo("b");
    cluster.registrar(
        new Node("b", "b", 50051, 50052, "a", 100, 0, 0, clock.instant(), "ALIVE", null, true));
    cluster.agregar(c);
    repair.reparar();
    verify(commands).enviar(eq("b"), argThat(command -> command instanceof DeleteBlock));
    verify(commands, never()).enviar(eq("c"), any());
  }

  @Test
  void noActuarConBrokerCaido() {
    when(broker.estaConectado()).thenReturn(false);
    cluster.quitar("blk", 1, "b", true);
    repair.reparar();
    verifyNoInteractions(commands);
  }

  @Test
  void noInventarFuenteSiNoQuedanCopias() {
    cluster.quitar("blk", 1, "a", true);
    cluster.quitar("blk", 1, "b", true);
    repair.reparar();
    verifyNoInteractions(commands);
  }
}
