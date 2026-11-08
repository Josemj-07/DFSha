package co.edu.upb.dfsha.node.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockKeyTest {

    @Test
    void aceptaUnIdValido() {
        var key = new BlockKey("blk_01JBX4", 3);

        assertEquals("blk_01JBX4:3", key.obtenerAlcance());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd", "blk_../x", "blk_a/b", "blk_", "otro_01", "blk_a b", ""})
    void rechazaIdsQuePodrianSalirseDelDirectorio(String id) {
        assertThrows(IllegalArgumentException.class, () -> new BlockKey(id, 1));
    }

    @Test
    void rechazaIdNulo() {
        assertThrows(IllegalArgumentException.class, () -> new BlockKey(null, 1));
    }

    @Test
    void rechazaGeneracionMenorQueUno() {
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("blk_01", 0));
    }
}
