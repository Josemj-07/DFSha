package co.edu.upb.dfsha.node.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockGrantTest {

    private final BlockKey bloque = new BlockKey("blk_01", 1);
    private final BlockGrant soloLectura = new BlockGrant("jose", Set.of(Op.READ), Set.of("blk_01:1"));

    @Test
    void permiteLaOperacionYElBloqueDelToken() {
        assertTrue(soloLectura.permite(Op.READ, bloque));
    }

    @Test
    void niegaUnaOperacionQueElTokenNoIncluye() {
        assertFalse(soloLectura.permite(Op.WRITE, bloque));
    }

    @Test
    void niegaOtroBloque() {
        assertFalse(soloLectura.permite(Op.READ, new BlockKey("blk_02", 1)));
    }

    @Test
    void niegaOtraGeneracionDelMismoBloque() {
        assertFalse(soloLectura.permite(Op.READ, new BlockKey("blk_01", 2)));
    }
}
