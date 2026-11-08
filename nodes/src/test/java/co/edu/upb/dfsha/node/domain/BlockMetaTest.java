package co.edu.upb.dfsha.node.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockMetaTest {

    private static final String SHA_VACIO = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final BlockKey bloque = new BlockKey("blk_01", 1);

    @Test
    void rechazaUnHashMalFormado() {
        assertThrows(IllegalArgumentException.class, () -> new BlockMeta(bloque, 0, "abc"));
    }

    @Test
    void rechazaLongitudNegativa() {
        assertThrows(IllegalArgumentException.class, () -> new BlockMeta(bloque, -1, SHA_VACIO));
    }

    @Test
    void construyeElTextoDelReciboComoLoEsperaElServidor() {
        var meta = new BlockMeta(bloque, 0, SHA_VACIO);

        assertEquals("dfsha-receipt-v1|blk_01|1|0|" + SHA_VACIO + "|dn-1",
                Receipt.construirContenidoFirmado("dn-1", meta));
    }
}
