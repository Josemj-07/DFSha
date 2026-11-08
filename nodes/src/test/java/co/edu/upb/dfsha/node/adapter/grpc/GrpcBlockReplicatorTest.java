package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.application.ReplicateBlockService;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.ReplicateBlockCommand;
import co.edu.upb.dfsha.node.domain.ReplicationFailedException;
import co.edu.upb.dfsha.node.port.BlockWriter;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Re-replicación real: el bloque de un almacén local llega por gRPC a un nodo destino. */
class GrpcBlockReplicatorTest {

    private static final BlockKey BLOQUE = new BlockKey("blk_01", 1);

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private TestNode destino;
    private GrpcReplicaClient replicas;
    private ReplicateBlockService servicio;
    private byte[] datos;

    @BeforeEach
    void preparar() throws IOException, NoSuchAlgorithmException {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        destino = new TestNode("dn-3", dir, scheduler, Duration.ofSeconds(2), Duration.ofSeconds(2));
        var origen = new FileSystemBlockStore(new BlockPaths(dir.resolve("origen")), new MetaFileCodec(), 0);
        datos = new byte[3 * 1024 * 1024 + 7];
        new Random(5).nextBytes(datos);
        BlockWriter writer = origen.crear(BLOQUE, datos.length);
        writer.escribir(ByteBuffer.wrap(datos));
        writer.confirmar(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos)));
        replicas = new GrpcReplicaClient(addr -> InProcessChannelBuilder.forName(addr).build(), scheduler,
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        var replicador = new GrpcBlockReplicator(replicas, new TransferRateLimiter(1_000_000_000L), Duration.ofSeconds(10));
        servicio = new ReplicateBlockService(origen, replicador);
    }

    @AfterEach
    void detener() throws InterruptedException {
        replicas.close();
        destino.close();
        scheduler.shutdownNow();
    }

    @Test
    void copiaElBloqueIdenticoAlDestino() throws IOException {
        servicio.ejecutar(comando("w:blk_01:1", destino.direccion));

        try (var entrada = Channels.newInputStream(destino.store.abrir(BLOQUE, 0))) {
            assertArrayEquals(datos, entrada.readAllBytes());
        }
    }

    @Test
    void siElDestinoYaLoTieneCuentaComoExito() {
        servicio.ejecutar(comando("w:blk_01:1", destino.direccion));

        assertDoesNotThrow(() -> servicio.ejecutar(comando("w:blk_01:1", destino.direccion)));
    }

    @Test
    void unDestinoCaidoEsUnaFalla() {
        assertThrows(ReplicationFailedException.class, () -> servicio.ejecutar(comando("w:blk_01:1", "no-existe")));
    }

    @Test
    void sinPermisoDeEscrituraEnElDestinoEsUnaFalla() {
        assertThrows(ReplicationFailedException.class,
                () -> servicio.ejecutar(comando("r:blk_01:1", destino.direccion)));
    }

    private static ReplicateBlockCommand comando(String token, String direccion) {
        return new ReplicateBlockCommand("c-1", BLOQUE, "dn-3", direccion, token);
    }
}
