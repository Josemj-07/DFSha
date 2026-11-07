package co.edu.upb.dfsha.server.cluster.application;

import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class GarbageCollector {
  private final ClusterState cluster;
  private final MetadataStore store;
  private final CommandGateway commands;
  private final BrokerStatus broker;
  private final Clock clock;

  public GarbageCollector(
      ClusterState cluster,
      MetadataStore store,
      CommandGateway commands,
      BrokerStatus broker,
      Clock clock) {
    this.cluster = cluster;
    this.store = store;
    this.commands = commands;
    this.broker = broker;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 60000)
  public void limpiar() {
    if (cluster.estaSeguro() || !broker.estaConectado()) return;
    var inventory = cluster.obtenerInventario();
    var eligible =
        store.mutar(
            s -> {
              var referenced = BlockReferences.obtener(s);
              Set<String> keys = new HashSet<>();
              for (String key : inventory.keySet())
                if (!referenced.containsKey(key) && !s.pendingBlocks.containsKey(key))
                  s.unreferenced.putIfAbsent(key, clock.instant());
              s.unreferenced.forEach(
                  (key, time) -> {
                    if (!referenced.containsKey(key)
                        && !time.plusSeconds(600).isAfter(clock.instant())) keys.add(key);
                  });
              s.pendingBlocks.forEach(
                  (key, time) -> {
                    if (!referenced.containsKey(key)
                        && !time.plusSeconds(3600).isAfter(clock.instant())) keys.add(key);
                  });
              for (String key : keys)
                if (!inventory.containsKey(key) || inventory.get(key).isEmpty()) {
                  s.unreferenced.remove(key);
                  s.pendingBlocks.remove(key);
                  s.blocks.remove(key);
                }
              return keys;
            });
    for (String key : eligible)
      for (var replica : inventory.getOrDefault(key, Set.of()))
        commands.enviar(
            replica.nodeId(),
            new co.edu.upb.dfsha.server.cluster.domain.DeleteBlock(
                UUID.randomUUID().toString(), replica.blockId(), replica.gen()));
  }
}
