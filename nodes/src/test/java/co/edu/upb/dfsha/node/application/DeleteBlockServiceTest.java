package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.domain.BlockEvent;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.DeleteBlockCommand;
import co.edu.upb.dfsha.node.port.BlockWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteBlockServiceTest {

    private static final String SHA_VACIO = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final BlockKey BLOQUE = new BlockKey("blk_01", 1);

    @TempDir
    Path dataDir;

    private FileSystemBlockStore store;
    private final RecordingPublisher publicador = new RecordingPublisher();
    private DeleteBlockService servicio;

    @BeforeEach
    void preparar() {
        store = new FileSystemBlockStore(new BlockPaths(dataDir), new MetaFileCodec(), 0);
        servicio = new DeleteBlockService("dn-1", store, publicador);
    }

    @Test
    void borraElBloqueYAvisaConSusDatos() {
        BlockWriter writer = store.crear(BLOQUE, 0);
        writer.escribir(ByteBuffer.allocate(0));
        writer.confirmar(SHA_VACIO);

        servicio.ejecutar(new DeleteBlockCommand("c-1", BLOQUE));

        assertTrue(store.buscar(BLOQUE).isEmpty());
        var publicado = publicador.publicados.getFirst();
        assertEquals("block.deleted.dn-1", publicado.routingKey());
        var evento = (BlockEvent) publicado.evento();
        assertEquals("blk_01", evento.blockId());
        assertEquals(SHA_VACIO, evento.sha256());
    }

    @Test
    void borrarUnBloqueQueNoExisteTambienAvisa() {
        servicio.ejecutar(new DeleteBlockCommand("c-1", BLOQUE));
        servicio.ejecutar(new DeleteBlockCommand("c-2", BLOQUE));

        assertEquals(2, publicador.publicados.size());
        assertEquals("block.deleted.dn-1", publicador.publicados.get(1).routingKey());
    }
}
