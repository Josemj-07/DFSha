package co.edu.upb.dfsha.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import co.edu.upb.dfsha.server.ServerApplication;
import co.edu.upb.dfsha.server.cluster.application.*;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.support.MutableClock;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;

@EnabledIfEnvironmentVariable(named = "DFS_TEST_RABBIT_PORT", matches = "[0-9]+")
class RealGarbageCollectorTest extends BrokerFixture {
  @TempDir Path dir;

  @Test
  void borrarCopiasFisicasSoloTrasLaGraciaYLosEventos() throws Exception {
    configurar(dir);
    String tls = material.resolve("server").toString();
    try (var context =
        new SpringApplicationBuilder(ServerApplication.class)
            .initializers(
                application ->
                    ((org.springframework.context.support.GenericApplicationContext) application)
                        .registerBean(
                            "relojDePrueba",
                            Clock.class,
                            MutableClock::new,
                            definition -> definition.setPrimary(true)))
            .run(
                "--server.port=" + java.net.URI.create(base).getPort(),
                "--dfs.data-dir=" + data,
                "--dfs.tls-dir=" + tls,
                "--dfs.block-size-bytes=4",
                "--dfs.admin-password=server-check-admin-1234",
                "--spring.rabbitmq.host=localhost",
                "--spring.rabbitmq.port=" + System.getenv("DFS_TEST_RABBIT_PORT"),
                "--spring.rabbitmq.password=server-check-pass-1234",
                "--spring.ssl.bundle.pem.server.keystore.certificate=file:" + tls + "/tls.crt",
                "--spring.ssl.bundle.pem.server.keystore.private-key=file:" + tls + "/tls.key",
                "--spring.ssl.bundle.pem.broker.truststore.certificate=file:" + tls + "/ca.crt")) {
      var factory = new ConnectionFactory();
      factory.setHost("localhost");
      factory.setPort(Integer.parseInt(System.getenv("DFS_TEST_RABBIT_PORT")));
      factory.setVirtualHost("dfsha");
      factory.setPassword("server-check-pass-1234");
      factory.useSslProtocol(ssl);
      factory.enableHostnameVerification();
      factory.setTopologyRecoveryEnabled(false);
      try (var a =
              new AmqpTestNode(
                  "dn-1", material.resolve("dn-1"), dir.resolve("a"), factory, mapper);
          var b =
              new AmqpTestNode(
                  "dn-2", material.resolve("dn-2"), dir.resolve("b"), factory, mapper)) {
        a.registrar();
        b.registrar();
        var cluster = context.getBean(ClusterState.class);
        esperar(() -> !cluster.estaSeguro(), 15);
        String token = ingresar();
        var opened =
            mapper.readTree(
                solicitar(
                        "POST",
                        "/v1/files:open",
                        Map.of("path", "/home/admin/f", "mode", "w", "create", true),
                        token)
                    .body());
        String handle = opened.get("handle").asText(),
            fencing = opened.get("lease").get("fencingToken").asText();
        var allocation =
            mapper.readTree(
                solicitar(
                        "POST",
                        "/v1/files/" + handle + ":allocate",
                        Map.of("firstIdx", 0, "count", 1),
                        token)
                    .body());
        String block = allocation.get("blocks").get(0).get("blockId").asText();
        byte[] content = "hola".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var receipts =
            List.of(a.escribir(block, 1, content, hash), b.escribir(block, 1, content, hash));
        String fileHash =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256").digest(HexFormat.of().parseHex(hash)));
        var published =
            solicitar(
                "POST",
                "/v1/files/" + handle + ":commit",
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
                            receipts))),
                token);
        assertEquals(200, published.statusCode(), published.body());
        assertEquals(
            204, solicitar("POST", "/v1/files/" + handle + ":close", null, token).statusCode());
        assertEquals(
            204, solicitar("DELETE", "/v1/fs/files?path=/home/admin/f", null, token).statusCode());
        var gc = context.getBean(GarbageCollector.class);
        gc.limpiar();
        assertTrue(Files.exists(dir.resolve("a/" + block + "-1.blk")));
        assertTrue(Files.exists(dir.resolve("b/" + block + "-1.blk")));
        ((MutableClock) context.getBean(Clock.class)).avanzar(601);
        gc.limpiar();
        esperar(
            () ->
                !Files.exists(dir.resolve("a/" + block + "-1.blk"))
                    && !Files.exists(dir.resolve("b/" + block + "-1.blk")),
            15);
        esperar(() -> !cluster.obtenerInventario().containsKey(block + ":1"), 15);
        gc.limpiar();
        assertFalse(
            context
                .getBean(MetadataStore.class)
                .<Boolean>leer(state -> state.blocks.containsKey(block + ":1")));
      }
    }
  }
}
