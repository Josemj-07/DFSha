package co.edu.upb.dfsha.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.rabbitmq.client.ConnectionFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "DFS_TEST_RABBIT_PORT", matches = "[0-9]+")
class RealBrokerTest extends BrokerFixture {
  @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS)
  Path dir;

  @AfterEach
  void cerrar() throws Exception {
    detener();
  }

  @Test
  void confirmarConAmqpsYRecuperarTrasKill9() throws Exception {
    configurar(dir);
    iniciar();
    String token = ingresar();
    assertEquals(
        503,
        solicitar(
                "POST",
                "/v1/files:open",
                Map.of("path", "/home/admin/f", "mode", "w", "create", true),
                token)
            .statusCode());
    var factory = new ConnectionFactory();
    factory.setHost("localhost");
    factory.setPort(Integer.parseInt(System.getenv("DFS_TEST_RABBIT_PORT")));
    factory.setVirtualHost("dfsha");
    factory.setPassword("server-check-pass-1234");
    factory.useSslProtocol(ssl);
    factory.enableHostnameVerification();
    factory.setTopologyRecoveryEnabled(false);
    try (var a =
            new AmqpTestNode("dn-1", material.resolve("dn-1"), dir.resolve("a"), factory, mapper);
        var b =
            new AmqpTestNode("dn-2", material.resolve("dn-2"), dir.resolve("b"), factory, mapper)) {
      a.registrar();
      b.registrar();
      esperarNodos(token);
      var opened =
          solicitar(
              "POST",
              "/v1/files:open",
              Map.of("path", "/home/admin/f", "mode", "w", "create", true),
              token);
      assertEquals(200, opened.statusCode(), opened.body());
      var open = mapper.readTree(opened.body());
      String handle = open.get("handle").asText(),
          fencing = open.get("lease").get("fencingToken").asText();
      var allocated =
          solicitar(
              "POST",
              "/v1/files/" + handle + ":allocate",
              Map.of("firstIdx", 0, "count", 1),
              token);
      assertEquals(200, allocated.statusCode(), allocated.body());
      String block = mapper.readTree(allocated.body()).get("blocks").get(0).get("blockId").asText();
      byte[] content = "hola".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
      var receipts =
          List.of(a.escribir(block, 1, content, hash), b.escribir(block, 1, content, hash));
      String fileHash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(HexFormat.of().parseHex(hash)));
      var commit =
          Map.of(
              "fencingToken",
              fencing,
              "size",
              4,
              "fileSha256",
              fileHash,
              "blocks",
              List.of(
                  Map.of(
                      "idx",
                      0,
                      "blockId",
                      block,
                      "gen",
                      1,
                      "len",
                      4,
                      "sha256",
                      hash,
                      "receipts",
                      receipts)));
      var published = solicitar("POST", "/v1/files/" + handle + ":commit", commit, token);
      assertEquals(200, published.statusCode(), published.body());
      assertEquals(1, mapper.readTree(published.body()).get("version").asLong());
      a.comprobarPermisos();
      detener();
      iniciar();
      token = ingresar();
      assertEquals(
          503,
          solicitar("POST", "/v1/files:open", Map.of("path", "/home/admin/f", "mode", "r"), token)
              .statusCode());
      a.registrar();
      b.registrar();
      esperarNodos(token);
      var read =
          solicitar("POST", "/v1/files:open", Map.of("path", "/home/admin/f", "mode", "r"), token);
      assertEquals(200, read.statusCode(), read.body());
      var result = mapper.readTree(read.body());
      assertEquals(1, result.get("baseVersion").asLong());
      assertEquals(fileHash, result.get("fileSha256").asText());
      assertEquals(2, result.get("blocks").get(0).get("replicas").size());
      if ("true".equals(System.getenv("DFS_TEST_BROKER_OUTAGE"))) comprobarCaidaBroker(a, b, token);
    }
  }

  private void esperarNodos(String token) throws Exception {
    esperar(
        () -> {
          try {
            return mapper.readTree(solicitar("GET", "/v1/admin/nodes", null, token).body()).size()
                == 2;
          } catch (Exception ex) {
            return false;
          }
        },
        20);
    // El listener procesa el registro antes que el block report en la misma cola.
    esperar(
        () -> {
          try {
            return solicitar(
                        "POST",
                        "/v1/files:open",
                        Map.of("path", "/home/admin/__probe", "mode", "r"),
                        token)
                    .statusCode()
                == 404;
          } catch (Exception ex) {
            return false;
          }
        },
        20);
  }

  private void comprobarCaidaBroker(AmqpTestNode a, AmqpTestNode b, String token) throws Exception {
    var pulses = Executors.newSingleThreadScheduledExecutor();
    pulses.scheduleAtFixedRate(
        () -> {
          try {
            a.latir();
            b.latir();
          } catch (Exception ignored) {
            /* El broker esta detenido durante la prueba. */
          }
        },
        0,
        3,
        TimeUnit.SECONDS);
    try {
      controlarBroker("stop");
      new CountDownLatch(1).await(62, TimeUnit.SECONDS);
      var nodes = mapper.readTree(solicitar("GET", "/v1/admin/nodes", null, token).body());
      for (var node : nodes) assertEquals("ALIVE", node.get("status").asText());
    } finally {
      controlarBroker("start");
      pulses.shutdownNow();
    }
    esperar(() -> a.estaConectado() && b.estaConectado(), 30);
    a.registrar();
    b.registrar();
    esperarNodos(token);
  }

  private void controlarBroker(String action) throws Exception {
    var process =
        new ProcessBuilder("podman", action, "dfsha-server-check-broker")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
  }
}
