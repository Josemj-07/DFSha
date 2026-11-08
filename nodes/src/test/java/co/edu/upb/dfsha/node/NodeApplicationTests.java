package co.edu.upb.dfsha.node;

import co.edu.upb.dfsha.node.config.GrpcServerLifecycle;
import co.edu.upb.dfsha.node.config.TestPki;
import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettySslContextChannelCredentials;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arranca el nodo completo, con la configuración real, certificados de prueba y sin RabbitMQ
 * (el nodo debe arrancar igual y seguir sirviendo bloques). Después se conecta por TLS 1.3.
 */
@SpringBootTest
class NodeApplicationTests {

    private static final Path DIR = crearEntorno();
    private static final int PUERTO_CLIENTES = buscarPuertoLibre();
    private static final int PUERTO_REPLICACION = buscarPuertoLibre();

    private final GrpcServerLifecycle servidores;

    @Autowired
    NodeApplicationTests(GrpcServerLifecycle servidores) {
        this.servidores = servidores;
    }

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        Path tls = DIR.resolve("tls");
        registro.add("dfs.node-id", () -> "dn-1");
        registro.add("dfs.az", () -> "az-a");
        registro.add("dfs.advertised-host", () -> "localhost");
        registro.add("dfs.client-port", () -> PUERTO_CLIENTES);
        registro.add("dfs.replication-port", () -> PUERTO_REPLICACION);
        registro.add("dfs.data-dir", () -> DIR.resolve("data").toString());
        registro.add("dfs.tls-dir", tls::toString);
        registro.add("spring.ssl.bundle.pem.broker.truststore.certificate", () -> "file:" + tls.resolve("ca.crt"));
        registro.add("spring.rabbitmq.host", () -> "localhost");
        registro.add("spring.rabbitmq.port", NodeApplicationTests::buscarPuertoLibre);
        registro.add("spring.rabbitmq.username", () -> "dn-1");
        registro.add("spring.rabbitmq.password", () -> "sin-broker");
    }

    @Test
    void arrancaSinRabbitMqYAtiendePorTls13ExigiendoToken() throws IOException, InterruptedException {
        assertTrue(servidores.isRunning());
        assertEquals(PUERTO_CLIENTES, servidores.obtenerPuerto("clientes"));
        assertTrue(Files.isDirectory(DIR.resolve("data").resolve("outbox")));

        var credenciales = NettySslContextChannelCredentials.create(GrpcSslContexts.forClient()
                .trustManager(DIR.resolve("tls").resolve("ca.crt").toFile()).protocols("TLSv1.3").build());
        ManagedChannel canal = Grpc.newChannelBuilderForAddress("localhost", PUERTO_CLIENTES, credenciales).build();
        try {
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer token-falso");
            var lectura = ReadRequest.newBuilder().setBlockId("blk_01").setGen(1).build();

            var error = assertThrows(StatusRuntimeException.class, () -> BlockServiceGrpc.newBlockingStub(canal)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .leerBloque(lectura).next());

            assertEquals(Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
        } finally {
            canal.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** Mismo contenido que monta Docker Compose en /tls: tls.crt, tls.key, ca.crt y blocktoken.pub. */
    private static Path crearEntorno() {
        try {
            Path dir = Files.createTempDirectory("dfsha-nodo");
            Path tls = Files.createDirectories(dir.resolve("tls"));
            var pki = new TestPki(dir.resolve("pki"), "CA de prueba");
            TestPki.Identity nodo = pki.emitir("dn-1", "dn-1", "localhost");
            Files.copy(nodo.certFile(), tls.resolve("tls.crt"));
            Files.copy(nodo.keyFile(), tls.resolve("tls.key"));
            Files.copy(pki.obtenerCaCert(), tls.resolve("ca.crt"));
            var claveTokens = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
            Files.writeString(tls.resolve("blocktoken.pub"), "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(claveTokens.getEncoded()) + "\n-----END PUBLIC KEY-----\n");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int buscarPuertoLibre() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
