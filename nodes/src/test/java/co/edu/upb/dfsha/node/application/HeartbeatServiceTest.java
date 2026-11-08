package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.Heartbeat;
import co.edu.upb.dfsha.node.port.BlockWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatServiceTest {

    private static final String SHA_VACIO = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Instant AHORA = Instant.parse("2026-11-08T06:00:00Z");

    @TempDir
    Path dataDir;

    @Test
    void publicaUnHeartbeatVolatilConElEstadoDelNodo() {
        var store = new FileSystemBlockStore(new BlockPaths(dataDir), new MetaFileCodec(), 0);
        BlockWriter writer = store.crear(new BlockKey("blk_01", 1), 0);
        writer.escribir(ByteBuffer.allocate(0));
        writer.confirmar(SHA_VACIO);
        var publicador = new RecordingPublisher();
        var servicio = new HeartbeatService("dn-1", store, publicador, () -> 3, Clock.fixed(AHORA, ZoneOffset.UTC));

        servicio.latir();

        var publicado = publicador.publicados.getFirst();
        assertEquals("node.heartbeat.dn-1", publicado.routingKey());
        assertFalse(publicado.durable());
        var latido = (Heartbeat) publicado.evento();
        assertEquals("dn-1", latido.nodeId());
        assertEquals(1, latido.blockCount());
        assertEquals(3, latido.activeConnections());
        assertEquals("2026-11-08T06:00:00Z", latido.sentAt());
        assertTrue(latido.capacityBytes() > 0);
        assertTrue(latido.usedBytes() >= 0 && latido.usedBytes() <= latido.capacityBytes());
    }
}
