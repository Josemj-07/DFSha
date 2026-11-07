package co.edu.upb.dfsha.server.cluster.infra;

import co.edu.upb.dfsha.server.cluster.application.*;
import co.edu.upb.dfsha.server.cluster.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class NodeEventListener {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(NodeEventListener.class);
  private final MessageAuthenticator auth;
  private final ClusterState cluster;
  private final CommandGateway commands;
  private final JsonMapper mapper;
  private final Clock clock;
  private final Map<String, Instant> resync = new HashMap<>();

  public NodeEventListener(
      MessageAuthenticator auth,
      ClusterState cluster,
      CommandGateway commands,
      JsonMapper mapper,
      Clock clock) {
    this.auth = auth;
    this.cluster = cluster;
    this.commands = commands;
    this.mapper = mapper;
    this.clock = clock;
  }

  @RabbitListener(queues = {"mds.heartbeats", "mds.events"})
  public void recibir(Message message) {
    try {
      JsonNode body = mapper.readTree(message.getBody());
      String node = texto(body, "nodeId");
      if (!auth.verificar(message, node)) {
        log.warn("Mensaje AMQP con identidad inconsistente descartado");
        return;
      }
      String key = message.getMessageProperties().getReceivedRoutingKey();
      if (key.startsWith("node.register.")) {
        registrar(body, node);
        return;
      }
      if (cluster.obtenerNodo(node) == null) {
        solicitarRegistro(node);
        return;
      }
      if (key.startsWith("node.heartbeat.")) {
        cluster.actualizarLatido(
            node,
            clock.instant(),
            numero(body, "usedBytes"),
            (int) numero(body, "activeConnections"));
        return;
      }
      if (key.startsWith("node.report.")) {
        List<Replica> blocks = new ArrayList<>();
        for (JsonNode b : body.required("blocks")) blocks.add(leerReplica(b, node));
        cluster.reportar(node, blocks);
        return;
      }
      Replica replica = leerReplica(body, node);
      if (key.startsWith("block.received.")) cluster.agregar(replica);
      else if (key.startsWith("block.deleted."))
        cluster.quitar(replica.blockId(), replica.gen(), node, false);
      else if (key.startsWith("block.corrupt."))
        cluster.quitar(replica.blockId(), replica.gen(), node, true);
      else throw new IllegalArgumentException("Evento desconocido");
    } catch (Exception ex) {
      throw new AmqpRejectAndDontRequeueException("Mensaje AMQP invalido", ex);
    }
  }

  private void registrar(JsonNode b, String id) throws Exception {
    int client = Math.toIntExact(numero(b, "clientPort")),
        replication = Math.toIntExact(numero(b, "replicationPort"));
    if (client < 1 || client > 65535 || replication < 1 || replication > 65535)
      throw new IllegalArgumentException("Puerto invalido");
    cluster.registrar(
        new Node(
            id,
            texto(b, "host"),
            client,
            replication,
            texto(b, "az"),
            numero(b, "capacityBytes"),
            0,
            0,
            clock.instant(),
            "ALIVE",
            auth.verificarCertificado(texto(b, "certPem"), id),
            false));
  }

  private synchronized void solicitarRegistro(String node) {
    Instant last = resync.get(node);
    if (last == null || !clock.instant().isBefore(last.plusSeconds(30))) {
      commands.enviar(node, new Resync(UUID.randomUUID().toString()));
      resync.put(node, clock.instant());
    }
  }

  private Replica leerReplica(JsonNode b, String node) {
    return new Replica(
        node, texto(b, "blockId"), numero(b, "gen"), numero(b, "length"), texto(b, "sha256"));
  }

  private String texto(JsonNode b, String key) {
    String value = b.required(key).asText();
    if (value.isBlank()) throw new IllegalArgumentException("Campo vacio");
    return value;
  }

  private long numero(JsonNode b, String key) {
    var n = b.required(key);
    if (!n.isIntegralNumber() || n.asLong() < 0)
      throw new IllegalArgumentException("Numero invalido");
    return n.asLong();
  }
}
