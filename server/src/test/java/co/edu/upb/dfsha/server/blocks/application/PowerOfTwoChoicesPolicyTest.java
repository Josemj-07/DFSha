package co.edu.upb.dfsha.server.blocks.application;

import static org.junit.jupiter.api.Assertions.*;

import co.edu.upb.dfsha.server.cluster.domain.Node;
import co.edu.upb.dfsha.server.common.DfsException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class PowerOfTwoChoicesPolicyTest {
  final PowerOfTwoChoicesPolicy policy =
      new PowerOfTwoChoicesPolicy(new java.security.SecureRandom());

  Node crear(String id, String az, long capacity) {
    return new Node(id, id, 50051, 50052, az, capacity, 0, 0, Instant.EPOCH, "ALIVE", null, true);
  }

  @Test
  void elegirZonasDistintas() {
    var nodes = List.of(crear("a", "a", 100), crear("b", "b", 100), crear("c", "c", 100));
    for (int i = 0; i < 100; i++) {
      var chosen = policy.elegir(nodes, 10, Set.of());
      assertNotEquals(chosen.getFirst().az(), chosen.getLast().az());
    }
  }

  @Test
  void respetarExcluidos() {
    var chosen =
        policy.elegir(
            List.of(crear("a", "a", 100), crear("b", "b", 100), crear("c", "c", 100)),
            10,
            Set.of("a"));
    assertTrue(chosen.stream().noneMatch(n -> n.nodeId().equals("a")));
  }

  @Test
  void rechazarMismaZona() {
    assertThrows(
        DfsException.class,
        () -> policy.elegir(List.of(crear("a", "a", 100), crear("b", "a", 100)), 10, Set.of()));
  }

  @Test
  void rechazarDiscosLlenos() {
    assertThrows(
        DfsException.class,
        () -> policy.elegir(List.of(crear("a", "a", 20), crear("b", "b", 100)), 10, Set.of()));
  }
}
