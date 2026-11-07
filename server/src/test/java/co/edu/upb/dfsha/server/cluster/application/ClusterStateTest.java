package co.edu.upb.dfsha.server.cluster.application;

import static org.junit.jupiter.api.Assertions.*;

import co.edu.upb.dfsha.server.cluster.domain.*;
import co.edu.upb.dfsha.server.support.MutableClock;
import java.util.*;
import org.junit.jupiter.api.*;

class ClusterStateTest {
  final ClusterState cluster = new ClusterState();
  final MutableClock clock = new MutableClock();

  void registrar(String id) {
    cluster.registrar(
        new Node(id, id, 50051, 50052, id, 100, 0, 0, clock.instant(), "ALIVE", null, false));
  }

  @Test
  void detectarYRecuperar() {
    registrar("a");
    clock.avanzar(10);
    cluster.detectar(clock.instant());
    assertEquals("SUSPECT", cluster.obtenerNodo("a").status());
    clock.avanzar(20);
    cluster.detectar(clock.instant());
    assertEquals("DEAD", cluster.obtenerNodo("a").status());
    cluster.actualizarLatido("a", clock.instant(), 10, 1);
    assertEquals("ALIVE", cluster.obtenerNodo("a").status());
  }

  @Test
  void reiniciarTiemposAlReconectar() {
    registrar("a");
    clock.avanzar(100);
    cluster.reiniciarUltimoContacto(clock.instant());
    cluster.detectar(clock.instant());
    assertEquals("ALIVE", cluster.obtenerNodo("a").status());
  }

  @Test
  void salirModoSeguroConDosReportes() {
    registrar("a");
    registrar("b");
    cluster.reportar("a", List.of());
    assertTrue(cluster.estaSeguro());
    cluster.reportar("b", List.of());
    assertFalse(cluster.estaSeguro());
  }

  @Test
  void reemplazarReporteSinDuplicados() {
    registrar("a");
    var r = new Replica("a", "blk", 1, 10, "sha");
    cluster.reportar("a", List.of(r));
    cluster.agregar(r);
    assertEquals(1, cluster.obtenerReplicas("blk", 1, true).size());
    cluster.reportar("a", List.of());
    assertTrue(cluster.obtenerReplicas("blk", 1, true).isEmpty());
  }
}
