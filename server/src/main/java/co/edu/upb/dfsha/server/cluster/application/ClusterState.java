package co.edu.upb.dfsha.server.cluster.application;

import co.edu.upb.dfsha.server.cluster.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ClusterState {
  private final Map<String, Node> nodes = new HashMap<>();
  private final Map<String, Set<Replica>> replicas = new HashMap<>();
  private final Map<String, Instant> deadSince = new HashMap<>();
  private final Map<String, Set<String>> corrupt = new HashMap<>();
  private final Map<String, Set<String>> unreachable = new HashMap<>();
  private boolean safe = true;

  public synchronized List<Node> obtenerNodos() {
    return List.copyOf(nodes.values());
  }

  public synchronized Node obtenerNodo(String id) {
    return nodes.get(id);
  }

  public synchronized void registrar(Node node) {
    nodes.put(node.nodeId(), node);
  }

  public synchronized void actualizarLatido(String id, Instant now, long used, int connections) {
    Node n = nodes.get(id);
    if (n != null) {
      nodes.put(id, n.actualizar(now, "ALIVE", used, connections, n.reported()));
      deadSince.remove(id);
    }
  }

  public synchronized void reportar(String id, List<Replica> blocks) {
    replicas.values().forEach(set -> set.removeIf(r -> r.nodeId().equals(id)));
    Set<String> reported =
        blocks.stream()
            .map(b -> b.blockId() + ":" + b.gen())
            .collect(java.util.stream.Collectors.toSet());
    corrupt.forEach(
        (key, bad) -> {
          if (!reported.contains(key)) bad.remove(id);
        });
    unreachable.values().forEach(nodes -> nodes.remove(id));
    blocks.forEach(this::registrarReplica);
    Node n = nodes.get(id);
    if (n != null)
      nodes.put(
          id, n.actualizar(n.lastContact(), n.status(), n.usedBytes(), n.connections(), true));
    if (nodes.values().stream().filter(Node::reported).count() >= 2) safe = false;
  }

  public synchronized void agregar(Replica replica) {
    var bad = corrupt.get(replica.blockId() + ":" + replica.gen());
    if (bad != null) bad.remove(replica.nodeId());
    var missing = unreachable.get(replica.blockId() + ":" + replica.gen());
    if (missing != null) missing.remove(replica.nodeId());
    registrarReplica(replica);
  }

  private void registrarReplica(Replica replica) {
    replicas
        .computeIfAbsent(replica.blockId() + ":" + replica.gen(), k -> new HashSet<>())
        .removeIf(r -> r.nodeId().equals(replica.nodeId()));
    replicas.get(replica.blockId() + ":" + replica.gen()).add(replica);
  }

  public synchronized void quitar(String block, long gen, String node, boolean damaged) {
    String key = block + ":" + gen;
    if (damaged) {
      corrupt.computeIfAbsent(key, k -> new HashSet<>()).add(node);
      var set = replicas.computeIfAbsent(key, k -> new HashSet<>());
      if (set.stream().noneMatch(r -> r.nodeId().equals(node)))
        set.add(new Replica(node, block, gen, 0, ""));
    } else {
      var missing = unreachable.get(key);
      if (missing != null) missing.remove(node);
      var set = replicas.get(key);
      if (set != null) {
        set.removeIf(r -> r.nodeId().equals(node));
        if (set.isEmpty()) replicas.remove(key);
      }
      var bad = corrupt.get(key);
      if (bad != null) {
        bad.remove(node);
        if (bad.isEmpty()) corrupt.remove(key);
      }
    }
  }

  public synchronized void marcarInalcanzable(String block, long gen, String node) {
    unreachable.computeIfAbsent(block + ":" + gen, k -> new HashSet<>()).add(node);
  }

  public synchronized Set<String> obtenerCorruptas(String block, long gen) {
    return Set.copyOf(corrupt.getOrDefault(block + ":" + gen, Set.of()));
  }

  public synchronized List<Replica> obtenerReplicas(String block, long gen, boolean alive) {
    return replicas.getOrDefault(block + ":" + gen, Set.of()).stream()
        .filter(
            r ->
                !alive
                    || (nodes.containsKey(r.nodeId())
                        && !nodes.get(r.nodeId()).status().equals("DEAD")
                        && !corrupt.getOrDefault(block + ":" + gen, Set.of()).contains(r.nodeId())
                        && !unreachable
                            .getOrDefault(block + ":" + gen, Set.of())
                            .contains(r.nodeId())))
        .sorted(
            Comparator.comparing(
                    (Replica r) ->
                        nodes.get(r.nodeId()) == null
                            || !nodes.get(r.nodeId()).status().equals("ALIVE"))
                .thenComparing(Replica::nodeId))
        .toList();
  }

  public synchronized Map<String, Set<Replica>> obtenerInventario() {
    var result = new HashMap<String, Set<Replica>>();
    replicas.forEach((k, v) -> result.put(k, Set.copyOf(v)));
    return result;
  }

  public synchronized boolean estaSeguro() {
    return safe;
  }

  public synchronized void reiniciarUltimoContacto(Instant now) {
    nodes.replaceAll(
        (id, n) -> n.actualizar(now, n.status(), n.usedBytes(), n.connections(), n.reported()));
    deadSince.clear();
  }

  public synchronized void detectar(Instant now) {
    nodes.replaceAll(
        (id, n) -> {
          long seconds = Duration.between(n.lastContact(), now).toSeconds();
          String status = seconds >= 30 ? "DEAD" : seconds >= 10 ? "SUSPECT" : "ALIVE";
          if (status.equals("DEAD")) deadSince.putIfAbsent(id, now);
          else deadSince.remove(id);
          return n.actualizar(
              n.lastContact(), status, n.usedBytes(), n.connections(), n.reported());
        });
  }

  public synchronized boolean requiereReparacion(String key, Instant now) {
    return !corrupt.getOrDefault(key, Set.of()).isEmpty()
        || replicas.getOrDefault(key, Set.of()).stream()
            .map(r -> deadSince.get(r.nodeId()))
            .filter(Objects::nonNull)
            .anyMatch(t -> !now.isBefore(t.plusSeconds(120)));
  }
}
