package co.edu.upb.dfsha.server.cluster.application;

import static org.mockito.Mockito.*;

import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.cluster.domain.*;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.journal.domain.ReferenceCounter;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import co.edu.upb.dfsha.server.support.*;
import java.util.*;
import org.junit.jupiter.api.*;

class GarbageCollectorTest {
  final MutableClock clock = new MutableClock();
  final MemoryStore store = new MemoryStore(clock);
  final ClusterState cluster = new ClusterState();
  final CommandGateway commands = mock(CommandGateway.class);
  final BrokerStatus broker = mock(BrokerStatus.class);
  GarbageCollector gc;

  @BeforeEach
  void preparar() {
    when(broker.estaConectado()).thenReturn(true);
    for (String id : List.of("a", "b")) {
      cluster.registrar(
          new Node(id, id, 1, 2, id, 100, 0, 0, clock.instant(), "ALIVE", null, true));
      cluster.reportar(id, List.of());
    }
    cluster.agregar(new Replica("a", "blk", 1, 4, "a"));
    gc = new GarbageCollector(cluster, store, commands, broker, clock);
  }

  @Test
  void respetarGracia() {
    gc.limpiar();
    verifyNoInteractions(commands);
    clock.avanzar(601);
    gc.limpiar();
    verify(commands).enviar(eq("a"), argThat(m -> m.type().equals("DELETE_BLOCK")));
  }

  @Test
  void conservarLectores() {
    store.mutar(
        s -> {
          s.handles.put(
              "h",
              new Handle(
                  "h",
                  "ana",
                  1,
                  "r",
                  1,
                  List.of(new Block(0, "blk", 1, 4, "a")),
                  null,
                  clock.instant().plusSeconds(1000),
                  Map.of(),
                  null,
                  null));
          return null;
        });
    gc.limpiar();
    clock.avanzar(601);
    gc.limpiar();
    verifyNoInteractions(commands);
  }

  @Test
  void esperarUnaHoraParaHuerfanos() {
    store.mutar(
        s -> {
          s.pendingBlocks.put("blk:1", clock.instant());
          return null;
        });
    clock.avanzar(601);
    gc.limpiar();
    verifyNoInteractions(commands);
    clock.avanzar(3000);
    gc.limpiar();
    verify(commands).enviar(eq("a"), any(co.edu.upb.dfsha.server.cluster.domain.NodeCommand.class));
  }

  @Test
  void detenerConBrokerCaido() {
    when(broker.estaConectado()).thenReturn(false);
    clock.avanzar(10000);
    gc.limpiar();
    verifyNoInteractions(commands);
  }

  @Test
  void contarReferenciasDeCopias() {
    store.mutar(
        s -> {
          co.edu.upb.dfsha.server.support.TestNamespace.crearHome(s, "ana", clock);
          var block = new Block(0, "blk", 1, 4, "a");
          for (long id = 1; id <= 2; id++) {
            s.directories.poner("/" + id, id);
            s.inodes.put(
                id,
                new Inode(
                    id,
                    "ana",
                    "rw-------",
                    false,
                    4,
                    1,
                    "a",
                    clock.instant(),
                    clock.instant(),
                    List.of(block)));
          }
          ReferenceCounter.actualizar(s, clock.instant());
          org.junit.jupiter.api.Assertions.assertEquals(2, s.blocks.get("blk:1").refCount());
          s.directories.eliminar("/1");
          ReferenceCounter.actualizar(s, clock.instant());
          org.junit.jupiter.api.Assertions.assertEquals(1, s.blocks.get("blk:1").refCount());
          return null;
        });
  }
}
