package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.domain.ReplicateBlockCommand;
import co.edu.upb.dfsha.node.port.BlockWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicateBlockServiceTest {

    private static final BlockKey BLOQUE = new BlockKey("blk_01", 1);
    private static final ReplicateBlockCommand COMANDO =
            new ReplicateBlockCommand("c-1", BLOQUE, "dn-3", "10.0.3.14:50052", "token-del-servidor");

    @TempDir
    Path dataDir;

    @Test
    void entregaElBloqueLocalAlReplicador() {
        var store = new FileSystemBlockStore(new BlockPaths(dataDir), new MetaFileCodec(), 0);
        BlockWriter writer = store.crear(BLOQUE, 3);
        writer.escribir(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        writer.confirmar("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
        List<String> llamadas = new ArrayList<>();
        List<byte[]> copiados = new ArrayList<>();

        new ReplicateBlockService(store, (meta, datos, nodo, addr, token) -> {
            llamadas.add(meta.key().obtenerAlcance() + "->" + nodo + "@" + addr + " con " + token);
            try {
                copiados.add(Channels.newInputStream(datos).readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).ejecutar(COMANDO);

        assertEquals(List.of("blk_01:1->dn-3@10.0.3.14:50052 con token-del-servidor"), llamadas);
        assertArrayEquals(new byte[] {1, 2, 3}, copiados.getFirst());
    }

    @Test
    void siElBloqueNoEstaAquiFalla() {
        var store = new FileSystemBlockStore(new BlockPaths(dataDir), new MetaFileCodec(), 0);
        List<String> llamadas = new ArrayList<>();

        assertThrows(BlockNotFoundException.class, () -> new ReplicateBlockService(store,
                (meta, datos, nodo, addr, token) -> llamadas.add("no debería llamarse")).ejecutar(COMANDO));
        assertTrue(llamadas.isEmpty());
    }
}
