package co.edu.upb.dfsha.node.adapter.storage;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaFileCodecTest {

    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final MetaFileCodec codec = new MetaFileCodec();

    @Test
    void loQueEscribeLoPuedeLeer() {
        var meta = new BlockMeta(new BlockKey("blk_01", 3), 134217728, SHA);

        assertEquals(meta, codec.leer(codec.escribir(meta)).orElseThrow());
    }

    @Test
    void rechazaContenidoDaniado() {
        assertTrue(codec.leer("{\"blockId\":\"blk_01\",\"gen\":1").isEmpty());
        assertTrue(codec.leer("").isEmpty());
    }

    @Test
    void rechazaUnIdQueNoEsDeBloque() {
        assertTrue(codec.leer("{\"blockId\":\"../x\",\"gen\":1,\"length\":0,\"sha256\":\"" + SHA + "\"}").isEmpty());
    }
}
