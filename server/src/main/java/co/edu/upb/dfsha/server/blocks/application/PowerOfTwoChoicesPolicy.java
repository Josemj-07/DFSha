package co.edu.upb.dfsha.server.blocks.application;

import co.edu.upb.dfsha.server.cluster.domain.Node;
import co.edu.upb.dfsha.server.common.DfsException;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PowerOfTwoChoicesPolicy implements ReplicaPlacementPolicy {
  private final java.util.random.RandomGenerator random;

  public PowerOfTwoChoicesPolicy(SecureRandom random) {
    this.random = random;
  }

  @Override
  public List<Node> elegir(List<Node> candidates, long blockSize, Set<String> excluded) {
    var nodes =
        candidates.stream()
            .filter(
                n ->
                    n.status().equals("ALIVE")
                        && n.capacityBytes() - n.usedBytes() > 2 * blockSize
                        && !excluded.contains(n.nodeId()))
            .toList();
    DfsException.exigir(
        nodes.size() >= 2, 503, "INSUFFICIENT_NODES", "No hay dos nodos disponibles");
    Comparator<Node> score =
        Comparator.comparingInt(Node::connections)
            .thenComparingDouble(n -> (double) n.usedBytes() / n.capacityBytes());
    Node a = nodes.get(random.nextInt(nodes.size())), b = nodes.get(random.nextInt(nodes.size()));
    Node first = score.compare(a, b) <= 0 ? a : b;
    Node second =
        nodes.stream()
            .filter(n -> !n.az().equals(first.az()))
            .min(score)
            .orElseThrow(
                () -> new DfsException(503, "INSUFFICIENT_NODES", "Se requieren zonas distintas"));
    return List.of(first, second);
  }
}
