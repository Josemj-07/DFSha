package co.edu.upb.dfsha.server.cluster.infra;

import co.edu.upb.dfsha.server.cluster.application.*;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ClusterMetrics {
  public ClusterMetrics(MeterRegistry registry, ClusterState cluster, MetadataStore store) {
    for (String status : java.util.List.of("ALIVE", "SUSPECT", "DEAD"))
      registry.gauge(
          "dfs.nodes",
          java.util.List.of(io.micrometer.core.instrument.Tag.of("status", status)),
          cluster,
          c -> c.obtenerNodos().stream().filter(n -> n.status().equals(status)).count());
    registry.gauge(
        "dfs.blocks.under_replicated",
        cluster,
        c ->
            store.leer(BlockReferences::obtener).values().stream()
                .filter(b -> c.obtenerReplicas(b.blockId(), b.gen(), true).size() < 2)
                .count());
  }
}
