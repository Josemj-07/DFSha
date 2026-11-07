package co.edu.upb.dfsha.server.cluster.application;

import co.edu.upb.dfsha.server.auth.application.TokenIssuer;
import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.cluster.domain.*;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RepairService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(RepairService.class);
  private final ClusterState cluster;
  private final CommandGateway commands;
  private final MetadataStore store;
  private final TokenIssuer tokens;
  private final BrokerStatus broker;
  private final Clock clock;
  private final Map<String, Instant> jobs = new HashMap<>();

  public RepairService(
      ClusterState cluster,
      CommandGateway commands,
      MetadataStore store,
      TokenIssuer tokens,
      BrokerStatus broker,
      Clock clock) {
    this.cluster = cluster;
    this.commands = commands;
    this.store = store;
    this.tokens = tokens;
    this.broker = broker;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 30000)
  public synchronized void reparar() {
    if (cluster.estaSeguro() || !broker.estaConectado()) return;
    for (var entry : store.leer(BlockReferences::obtener).entrySet())
      repararBloque(entry.getKey(), entry.getValue());
    jobs.entrySet().removeIf(e -> !e.getValue().isAfter(clock.instant()));
  }

  private void repararBloque(String key, Block block) {
    var healthy = cluster.obtenerReplicas(block.blockId(), block.gen(), true);
    if (healthy.isEmpty()) {
      log.error("Bloque sin replicas vivas: {}", key);
      return;
    }
    var first = cluster.obtenerNodo(healthy.getFirst().nodeId());
    var second =
        healthy.stream()
            .filter(r -> !cluster.obtenerNodo(r.nodeId()).az().equals(first.az()))
            .findFirst();
    if (second.isPresent()) {
      Set<String> keep = Set.of(first.nodeId(), second.orElseThrow().nodeId());
      for (var replica : cluster.obtenerReplicas(block.blockId(), block.gen(), false))
        if (!keep.contains(replica.nodeId())) eliminarSobrante(key, block, replica.nodeId());
      return;
    }
    if (!cluster.requiereReparacion(key, clock.instant()) || ocupado("replicate:" + key)) return;
    var occupied =
        cluster.obtenerReplicas(block.blockId(), block.gen(), false).stream()
            .map(Replica::nodeId)
            .toList();
    var target =
        cluster.obtenerNodos().stream()
            .filter(
                n ->
                    n.status().equals("ALIVE")
                        && !n.az().equals(first.az())
                        && !occupied.contains(n.nodeId())
                        && n.capacityBytes() - n.usedBytes() > block.len())
            .findFirst();
    if (target.isEmpty()) return;
    var destination = target.orElseThrow();
    commands.enviar(
        first.nodeId(),
        new ReplicateBlock(
            UUID.randomUUID().toString(),
            block.blockId(),
            block.gen(),
            new ReplicationTarget(destination.nodeId(), destination.obtenerDireccionReplica()),
            tokens.emitirBloques("mds", "w", List.of(key))));
    jobs.put("replicate:" + key, clock.instant().plusSeconds(120));
  }

  private void eliminarSobrante(String key, Block block, String node) {
    String job = "delete:" + key + ":" + node;
    if (ocupado(job)) return;
    commands.enviar(
        node, new DeleteBlock(UUID.randomUUID().toString(), block.blockId(), block.gen()));
    jobs.put(job, clock.instant().plusSeconds(120));
  }

  private boolean ocupado(String key) {
    return jobs.getOrDefault(key, Instant.MIN).isAfter(clock.instant());
  }
}
