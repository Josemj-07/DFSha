package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockReport;
import co.edu.upb.dfsha.node.domain.NodeIdentity;
import co.edu.upb.dfsha.node.domain.NodeRegistration;
import co.edu.upb.dfsha.node.domain.ReportedBlock;
import co.edu.upb.dfsha.node.port.BlockWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockReportServiceTest {

    private static final String SHA_VACIO = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final NodeIdentity IDENTIDAD =
            new NodeIdentity("dn-1", "10.0.1.21", 50051, 50052, "us-east-1a", "-----BEGIN CERTIFICATE-----");

    @TempDir
    Path dataDir;

    private FileSystemBlockStore store;
    private final RecordingPublisher publicador = new RecordingPublisher();

    @BeforeEach
    void preparar() {
        store = new FileSystemBlockStore(new BlockPaths(dataDir), new MetaFileCodec(), 0);
    }

    @Test
    void primeroSeRegistraYDespuesReportaSusBloques() {
        guardarVacio(new BlockKey("blk_01", 1));
        guardarVacio(new BlockKey("blk_02", 3));

        new BlockReportService(IDENTIDAD, store, publicador).publicarTodo();

        assertEquals(List.of("node.register.dn-1", "node.report.dn-1"),
                publicador.publicados.stream().map(RecordingPublisher.Publicado::routingKey).toList());
        assertTrue(publicador.publicados.stream().allMatch(RecordingPublisher.Publicado::durable));
        var registro = (NodeRegistration) publicador.publicados.get(0).evento();
        assertEquals("10.0.1.21", registro.host());
        assertEquals(50051, registro.clientPort());
        assertEquals(50052, registro.replicationPort());
        assertEquals("us-east-1a", registro.az());
        var reporte = (BlockReport) publicador.publicados.get(1).evento();
        assertEquals("dn-1", reporte.nodeId());
        assertEquals(2, reporte.blocks().size());
        assertTrue(reporte.blocks().contains(new ReportedBlock("blk_02", 3, 0, SHA_VACIO)));
    }

    @Test
    void unNodoVacioReportaUnaListaVacia() {
        new BlockReportService(IDENTIDAD, store, publicador).publicarReporte();

        assertTrue(((BlockReport) publicador.publicados.getFirst().evento()).blocks().isEmpty());
    }

    private void guardarVacio(BlockKey key) {
        BlockWriter writer = store.crear(key, 0);
        writer.escribir(ByteBuffer.allocate(0));
        writer.confirmar(SHA_VACIO);
    }
}
