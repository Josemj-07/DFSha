package co.edu.upb.dfsha.server.blocks.application;

import co.edu.upb.dfsha.server.cluster.domain.Node;
import java.util.*;

public interface ReplicaPlacementPolicy {
  List<Node> elegir(List<Node> candidates, long blockSize, Set<String> excluded);
}
