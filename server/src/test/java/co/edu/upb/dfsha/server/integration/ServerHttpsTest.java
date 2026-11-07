package co.edu.upb.dfsha.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import co.edu.upb.dfsha.server.ServerApplication;
import co.edu.upb.dfsha.server.cluster.infra.NodeEventListener;
import co.edu.upb.dfsha.server.support.TestPki;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.util.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerHttpsTest {
  @TempDir static Path dir;
  ConfigurableApplicationContext context;
  HttpClient client;
  String base;
  final JsonMapper mapper = JsonMapper.builder().build();
  TestPki pki;

  @BeforeAll
  void iniciar() throws Exception {
    pki = new TestPki(dir.resolve("tls"));
    String tls = dir.resolve("tls").toString();
    context =
        new SpringApplicationBuilder(ServerApplication.class)
            .run(
                "--server.port=0",
                "--dfs.data-dir=" + dir.resolve("data"),
                "--dfs.tls-dir=" + tls,
                "--dfs.admin-password=contrasenia-admin-1234",
                "--dfs.block-size-bytes=4",
                "--spring.ssl.bundle.pem.server.keystore.certificate=file:" + tls + "/tls.crt",
                "--spring.ssl.bundle.pem.server.keystore.private-key=file:" + tls + "/tls.key",
                "--spring.ssl.bundle.pem.broker.truststore.certificate=file:" + tls + "/ca.crt",
                "--spring.rabbitmq.host=localhost",
                "--spring.rabbitmq.password=test",
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--management.health.rabbit.enabled=false");
    base = "https://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    var store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null, null);
    try (var stream = Files.newInputStream(dir.resolve("tls/ca.crt"))) {
      store.setCertificateEntry(
          "ca", CertificateFactory.getInstance("X.509").generateCertificate(stream));
    }
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var ssl = SSLContext.getInstance("TLS");
    ssl.init(null, trust.getTrustManagers(), null);
    client = HttpClient.newBuilder().sslContext(ssl).build();
  }

  @AfterAll
  void cerrar() {
    if (context != null) context.close();
  }

  HttpResponse<String> solicitar(String method, String path, Object data, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return client.send(
        builder
            .method(
                method,
                data == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(data)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String ingresar() throws Exception {
    var response =
        solicitar(
            "POST",
            "/v1/auth/login",
            Map.of("username", "admin", "password", "contrasenia-admin-1234"),
            null);
    assertEquals(200, response.statusCode());
    return mapper.readTree(response.body()).get("accessToken").asText();
  }

  @Test
  void verificarHttpsYAcceso() throws Exception {
    assertEquals(200, solicitar("GET", "/actuator/health", null, null).statusCode());
    assertEquals(401, solicitar("GET", "/v1/fs/stat?path=/home/admin", null, null).statusCode());
    var params = new SSLParameters();
    params.setProtocols(new String[] {"TLSv1.2"});
    var legacy =
        HttpClient.newBuilder().sslContext(client.sslContext()).sslParameters(params).build();
    assertThrows(
        java.io.IOException.class,
        () ->
            legacy.send(
                HttpRequest.newBuilder(URI.create(base + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString()));
    String token = ingresar();
    assertEquals(
        204,
        solicitar(
                "POST",
                "/v1/admin/users",
                Map.of("username", "ana", "password", "contrasenia-ana-1234", "role", "USER"),
                token)
            .statusCode());
    var login =
        solicitar(
            "POST",
            "/v1/auth/login",
            Map.of("username", "ana", "password", "contrasenia-ana-1234"),
            null);
    String userToken = mapper.readTree(login.body()).get("accessToken").asText();
    assertEquals(403, solicitar("GET", "/v1/admin/nodes", null, userToken).statusCode());
    assertEquals(
        403, solicitar("GET", "/v1/fs/stat?path=/home/admin", null, userToken).statusCode());
  }

  @Test
  void publicarConRecibosFirmadosYLeerSnapshot() throws Exception {
    String token = ingresar();
    assertEquals(
        503,
        solicitar(
                "POST",
                "/v1/files:open",
                Map.of("path", "/home/admin/f", "mode", "w", "create", true),
                token)
            .statusCode());
    var listener = context.getBean(NodeEventListener.class);
    Map<String, KeyPair> keys = new HashMap<>();
    for (String id : List.of("dn-1", "dn-2")) {
      var pair = pki.generar();
      keys.put(id, pair);
      enviar(
          listener,
          "node.register." + id,
          id,
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
              1000,
              "certPem",
              pki.obtenerPem(pki.firmar(id, pair, false))));
      enviar(listener, "node.report." + id, id, Map.of("nodeId", id, "blocks", List.of()));
    }
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
            "POST", "/v1/files/" + handle + ":allocate", Map.of("firstIdx", 0, "count", 1), token);
    assertEquals(200, allocated.statusCode(), allocated.body());
    var block = mapper.readTree(allocated.body()).get("blocks").get(0);
    String id = block.get("blockId").asText();
    String hash = co.edu.upb.dfsha.server.auth.application.AuthService.calcularHash("hola");
    List<Map<String, String>> receipts = new ArrayList<>();
    for (String node : keys.keySet()) {
      var signature = Signature.getInstance("SHA256withECDSA");
      signature.initSign(keys.get(node).getPrivate());
      signature.update(
          ("dfsha-receipt-v1|" + id + "|1|4|" + hash + "|" + node)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      receipts.add(
          Map.of("node", node, "sig", Base64.getEncoder().encodeToString(signature.sign())));
    }
    String fileHash =
        java.util.HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(java.util.HexFormat.of().parseHex(hash)));
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
                    id,
                    "gen",
                    1,
                    "len",
                    4,
                    "sha256",
                    hash,
                    "receipts",
                    receipts)));
    var result = solicitar("POST", "/v1/files/" + handle + ":commit", commit, token);
    assertEquals(200, result.statusCode(), result.body());
    assertEquals(
        200, solicitar("POST", "/v1/files/" + handle + ":commit", commit, token).statusCode());
    assertEquals(
        200, solicitar("POST", "/v1/files/" + handle + ":keepalive", null, token).statusCode());
    var read =
        solicitar("POST", "/v1/files:open", Map.of("path", "/home/admin/f", "mode", "r"), token);
    assertEquals(4, mapper.readTree(read.body()).get("size").asLong());
  }

  void enviar(NodeEventListener listener, String key, String node, Object body) {
    var properties = new MessageProperties();
    properties.setReceivedUserId(node);
    properties.setReceivedRoutingKey(key);
    listener.recibir(new Message(mapper.writeValueAsBytes(body), properties));
  }
}
