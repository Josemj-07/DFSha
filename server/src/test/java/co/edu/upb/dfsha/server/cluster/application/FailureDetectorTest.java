package co.edu.upb.dfsha.server.cluster.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.edu.upb.dfsha.server.cluster.domain.Node;
import co.edu.upb.dfsha.server.support.MutableClock;
import org.junit.jupiter.api.*;

class FailureDetectorTest {
  @Test
  void congelarMientrasBrokerCaido() {
    var clock = new MutableClock();
    var cluster = new ClusterState();
    var broker = mock(BrokerStatus.class);
    cluster.registrar(
        new Node("a", "a", 1, 2, "az", 100, 0, 0, clock.instant(), "ALIVE", null, true));
    clock.avanzar(100);
    new FailureDetector(cluster, broker, clock).revisar();
    assertEquals("ALIVE", cluster.obtenerNodo("a").status());
    when(broker.estaConectado()).thenReturn(true);
    cluster.reiniciarUltimoContacto(clock.instant());
    new FailureDetector(cluster, broker, clock).revisar();
    assertEquals("ALIVE", cluster.obtenerNodo("a").status());
  }
}
