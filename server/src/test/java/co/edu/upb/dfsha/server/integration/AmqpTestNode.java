package co.edu.upb.dfsha.server.integration;

import com.rabbitmq.client.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

final class AmqpTestNode implements AutoCloseable {
  private final String id;
  private final Path material, data;
  private final Connection connection;
  private final Channel channel;
  private final JsonMapper mapper;
  private final List<Map<String, Object>> blocks = new ArrayList<>();

  AmqpTestNode(String id, Path material, Path data, ConnectionFactory factory, JsonMapper mapper)
      throws Exception {
    this.id = id;
    this.material = material;
    this.data = data;
    this.mapper = mapper;
    factory.setUsername(id);
    connection = factory.newConnection();
    channel = connection.createChannel();
    Files.createDirectories(data);
    channel.basicConsume(
        "dn." + id + ".commands", false, (tag, delivery) -> recibirComando(delivery), tag -> {});
  }

  private synchronized void recibirComando(Delivery delivery) throws java.io.IOException {
    try {
      var command = mapper.readTree(delivery.getBody());
      String type = command.get("type").asText();
      if (type.equals("RESYNC")) registrar();
      else if (type.equals("DELETE_BLOCK"))
        eliminarBloque(command.get("blockId").asText(), command.get("gen").asLong());
      channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
    } catch (Exception ex) {
      channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
    }
  }

  private void eliminarBloque(String block, long gen) throws Exception {
    var previous =
        blocks.stream()
            .filter(
                b -> b.get("blockId").equals(block) && ((Number) b.get("gen")).longValue() == gen)
            .findFirst();
    Files.deleteIfExists(data.resolve(block + "-" + gen + ".blk"));
    try (var directory = FileChannel.open(data, StandardOpenOption.READ)) {
      directory.force(true);
    }
    blocks.removeIf(
        b -> b.get("blockId").equals(block) && ((Number) b.get("gen")).longValue() == gen);
    if (previous.isPresent()) {
      var event = new HashMap<String, Object>(previous.orElseThrow());
      event.put("nodeId", id);
      event.put("eventId", UUID.randomUUID().toString());
      publicar("block.deleted", event);
    }
  }

  synchronized void registrar() throws Exception {
    publicar(
        "node.register",
        Map.of(
            "nodeId",
            id,
            "host",
            id,
            "clientPort",
            50051,
            "replicationPort",
            50052,
            "az",
            id,
            "capacityBytes",
            1000000000L,
            "certPem",
            Files.readString(material.resolve("tls.crt"))));
    publicar("node.report", Map.of("nodeId", id, "blocks", List.copyOf(blocks)));
    latir();
  }

  synchronized void latir() throws Exception {
    if (connection.isOpen())
      publicar(
          "node.heartbeat",
          Map.of(
              "nodeId",
              id,
              "capacityBytes",
              1000000000L,
              "usedBytes",
              4L * blocks.size(),
              "activeConnections",
              0,
              "blockCount",
              blocks.size(),
              "sentAt",
              java.time.Instant.now().toString()));
  }

  synchronized Map<String, String> escribir(String block, long gen, byte[] content, String hash)
      throws Exception {
    try (var file =
        FileChannel.open(
            data.resolve(block + "-" + gen + ".blk"),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
      var buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) file.write(buffer);
      file.force(true);
    }
    blocks.add(Map.of("blockId", block, "gen", gen, "length", content.length, "sha256", hash));
    Map<String, Object> event = new HashMap<>(blocks.getLast());
    event.put("nodeId", id);
    event.put("eventId", UUID.randomUUID().toString());
    publicar("block.received", event);
    String pem =
        Files.readString(material.resolve("tls.key")).replaceAll("-----[A-Z ]+-----|\\s", "");
    var privateKey =
        KeyFactory.getInstance("EC")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    var signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(privateKey);
    signer.update(
        ("dfsha-receipt-v1|" + block + "|" + gen + "|" + content.length + "|" + hash + "|" + id)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return Map.of("node", id, "sig", Base64.getEncoder().encodeToString(signer.sign()));
  }

  synchronized boolean estaConectado() {
    return connection.isOpen();
  }

  synchronized void publicar(String prefix, Object body) throws Exception {
    channel.basicPublish(
        "dfs.events",
        prefix + "." + id,
        new AMQP.BasicProperties.Builder()
            .userId(id)
            .contentType("application/json")
            .deliveryMode(2)
            .build(),
        mapper.writeValueAsBytes(body));
  }

  void comprobarPermisos() throws Exception {
    Channel spoof = connection.createChannel();
    try {
      spoof.basicPublish(
          "dfs.events",
          "node.heartbeat.dn-2",
          new AMQP.BasicProperties.Builder().userId("dn-2").build(),
          new byte[0]);
      BrokerFixture.esperar(() -> !spoof.isOpen(), 10);
      org.junit.jupiter.api.Assertions.assertEquals(
          406, ((AMQP.Channel.Close) spoof.getCloseReason().getReason()).getReplyCode());
    } finally {
      if (spoof.isOpen()) spoof.close();
    }
    Channel denied = connection.createChannel();
    try {
      denied.basicPublish("dfs.commands", "dn-1", null, new byte[0]);
      BrokerFixture.esperar(() -> !denied.isOpen(), 10);
      org.junit.jupiter.api.Assertions.assertEquals(
          403, ((AMQP.Channel.Close) denied.getCloseReason().getReason()).getReplyCode());
    } finally {
      if (denied.isOpen()) denied.close();
    }
  }

  @Override
  public void close() throws Exception {
    connection.close();
  }
}
