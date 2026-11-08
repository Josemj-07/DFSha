package co.edu.upb.dfsha.node.config;

import co.edu.upb.dfsha.node.adapter.grpc.AuthInterceptor;
import co.edu.upb.dfsha.node.adapter.grpc.GrantEchoService;
import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.InvalidTokenException;
import co.edu.upb.dfsha.node.domain.Op;
import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettySslContextChannelCredentials;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comprueba con conexiones de red reales que solo se puede hablar con el nodo por TLS 1.3. */
class GrpcServerLifecycleTest {

    private static final ReadRequest LECTURA = ReadRequest.newBuilder().setBlockId("blk_01").setGen(1).build();
    private static final String CLIENTES = "clientes";
    private static final String REPLICACION = "replicacion";

    @TempDir
    Path dir;

    private TestPki pki;
    private GrpcServerLifecycle lifecycle;
    private final List<ManagedChannel> canales = new ArrayList<>();

    @BeforeEach
    void arrancar() {
        pki = new TestPki(dir.resolve("pki"), "CA de prueba");
        TestPki.Identity nodo = pki.emitir("dn-1", "localhost");
        var auth = new AuthInterceptor(token -> {
            if (!"token-bueno".equals(token)) {
                throw new InvalidTokenException("token inválido");
            }
            return new BlockGrant("jose", Set.of(Op.READ), Set.of("blk_01:1"));
        });
        lifecycle = new GrpcServerLifecycle(nodo.certFile(), nodo.keyFile(), auth, List.of(
                new GrpcEndpoint(CLIENTES, 0, List.of(new GrantEchoService())),
                new GrpcEndpoint(REPLICACION, 0, List.of(new GrantEchoService()))));
        lifecycle.start();
    }

    @AfterEach
    void detener() throws InterruptedException {
        for (ManagedChannel canal : canales) {
            canal.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        lifecycle.stop();
    }

    @Test
    void abreLosDosPuertos() {
        assertTrue(lifecycle.isRunning());
        assertNotEquals(lifecycle.obtenerPuerto(CLIENTES), lifecycle.obtenerPuerto(REPLICACION));
    }

    @Test
    void aceptaTls13ConLaCaCorrecta() throws IOException {
        for (String nombre : List.of(CLIENTES, REPLICACION)) {
            ManagedChannel canal = abrir(nombre, tls(pki.obtenerCaCert(), "TLSv1.3"));

            assertEquals("jose|token-bueno", leer(canal, "Bearer token-bueno"));
        }
    }

    @Test
    void conTlsPeroSinTokenRespondeUnauthenticated() throws IOException {
        ManagedChannel canal = abrir(CLIENTES, tls(pki.obtenerCaCert(), "TLSv1.3"));

        var error = assertThrows(StatusRuntimeException.class, () -> leer(canal, "Bearer otro"));

        assertEquals(Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
    }

    @Test
    void rechazaTls12() throws IOException {
        for (String nombre : List.of(CLIENTES, REPLICACION)) {
            ManagedChannel canal = abrir(nombre, tls(pki.obtenerCaCert(), "TLSv1.2"));

            assertThrows(StatusRuntimeException.class, () -> leer(canal, "Bearer token-bueno"));
        }
    }

    @Test
    void rechazaTextoPlano() {
        for (String nombre : List.of(CLIENTES, REPLICACION)) {
            ManagedChannel canal = abrir(nombre, InsecureChannelCredentials.create());

            assertThrows(StatusRuntimeException.class, () -> leer(canal, "Bearer token-bueno"));
        }
    }

    @Test
    void unClienteQueConfiaEnOtraCaNoSeConecta() throws IOException {
        TestPki atacante = new TestPki(dir.resolve("otra"), "CA atacante");
        ManagedChannel canal = abrir(CLIENTES, tls(atacante.obtenerCaCert(), "TLSv1.3"));

        assertThrows(StatusRuntimeException.class, () -> leer(canal, "Bearer token-bueno"));
    }

    @Test
    void stopCierraLosPuertos() {
        lifecycle.stop();

        assertFalse(lifecycle.isRunning());
        assertThrows(IllegalStateException.class, () -> lifecycle.obtenerPuerto(CLIENTES));
    }

    private ManagedChannel abrir(String servidor, ChannelCredentials credenciales) {
        ManagedChannel canal = Grpc.newChannelBuilderForAddress("localhost", lifecycle.obtenerPuerto(servidor),
                credenciales).build();
        canales.add(canal);
        return canal;
    }

    private static ChannelCredentials tls(Path ca, String version) throws IOException {
        return NettySslContextChannelCredentials.create(
                GrpcSslContexts.forClient().trustManager(ca.toFile()).protocols(version).build());
    }

    private static String leer(ManagedChannel canal, String authorization) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), authorization);
        return BlockServiceGrpc.newBlockingStub(canal)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .leerBloque(LECTURA)
                .next()
                .getData()
                .toStringUtf8();
    }
}
