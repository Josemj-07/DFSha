package co.edu.upb.dfsha.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.util.*;
import javax.net.ssl.*;
import tools.jackson.databind.json.JsonMapper;

abstract class BrokerFixture {
  final JsonMapper mapper = JsonMapper.builder().build();
  HttpClient http;
  SSLContext ssl;
  Path material, data, log;
  Process server;
  String base;

  void configurar(Path dir) throws Exception {
    material = Path.of(System.getenv("DFS_TEST_TLS_DIR"));
    data = dir.resolve("metadata");
    log = dir.resolve("server.log");
    var trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    try (var input = Files.newInputStream(material.resolve("server/ca.crt"))) {
      trust.setCertificateEntry(
          "ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    manager.init(trust);
    ssl = SSLContext.getInstance("TLSv1.3");
    ssl.init(null, manager.getTrustManagers(), null);
    http =
        HttpClient.newBuilder()
            .sslContext(ssl)
            .connectTimeout(java.time.Duration.ofSeconds(2))
            .build();
    try (var socket = new java.net.ServerSocket(0)) {
      base = "https://localhost:" + socket.getLocalPort();
    }
  }

  void iniciar() throws Exception {
    var args =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-jar",
                "build/libs/server-0.0.1-SNAPSHOT.jar",
                "--server.port=" + URI.create(base).getPort(),
                "--dfs.data-dir=" + data,
                "--dfs.tls-dir=" + material.resolve("server"),
                "--dfs.block-size-bytes=4",
                "--dfs.admin-password=server-check-admin-1234",
                "--spring.rabbitmq.host=localhost",
                "--spring.rabbitmq.port=" + System.getenv("DFS_TEST_RABBIT_PORT"),
                "--spring.rabbitmq.password=server-check-pass-1234"));
    String tls = material.resolve("server").toString();
    args.add("--spring.ssl.bundle.pem.server.keystore.certificate=file:" + tls + "/tls.crt");
    args.add("--spring.ssl.bundle.pem.server.keystore.private-key=file:" + tls + "/tls.key");
    args.add("--spring.ssl.bundle.pem.broker.truststore.certificate=file:" + tls + "/ca.crt");
    server =
        new ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .start();
    esperar(
        () -> {
          assertTrue(server.isAlive(), "El proceso del servidor termino; revise " + log);
          try {
            return solicitar("GET", "/actuator/health", null, null).statusCode() == 200;
          } catch (Exception ex) {
            return false;
          }
        },
        45);
  }

  HttpResponse<String> solicitar(String method, String path, Object body, String token)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(java.time.Duration.ofSeconds(5))
            .header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    return http.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String ingresar() throws Exception {
    var result =
        solicitar(
            "POST",
            "/v1/auth/login",
            Map.of("username", "admin", "password", "server-check-admin-1234"),
            null);
    assertEquals(200, result.statusCode(), result.body());
    return mapper.readTree(result.body()).get("accessToken").asText();
  }

  static void esperar(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
    long limit = System.nanoTime() + seconds * 1000000000L;
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < limit, "Se agoto el plazo de la comprobacion real");
      new java.util.concurrent.CountDownLatch(1)
          .await(100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
  }

  void detener() throws Exception {
    if (server != null) {
      server.destroyForcibly();
      assertTrue(server.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }
}
