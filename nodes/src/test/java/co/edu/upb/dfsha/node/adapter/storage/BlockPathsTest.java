package co.edu.upb.dfsha.node.adapter.storage;

import co.edu.upb.dfsha.node.domain.BlockKey;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPathsTest {

    private final Path dataDir = Path.of("/data");
    private final BlockPaths paths = new BlockPaths(dataDir);

    @Test
    void reparteLosBloquesPorLosUltimosDosCaracteres() {
        var key = new BlockKey("blk_3f2a-c7", 2);

        assertEquals(Path.of("/data/blocks/c7/blk_3f2a-c7_2.blk"), paths.obtenerCompleto(key));
        assertEquals(Path.of("/data/blocks/c7/blk_3f2a-c7_2.part"), paths.obtenerParcial(key));
    }

    @Test
    void todasLasRutasQuedanDentroDelAlmacen() {
        var key = new BlockKey("blk_x1", 1);

        assertTrue(paths.obtenerMeta(key).startsWith(Path.of("/data/blocks")));
    }

    @Test
    void recuperaLaClaveDesdeElNombre() {
        var key = new BlockKey("blk_3f2a-c7", 12);

        assertEquals(Optional.of(key), paths.interpretarNombre(paths.obtenerCompleto(key), BlockPaths.COMPLETO));
    }

    @Test
    void ignoraArchivosAjenos() {
        assertTrue(paths.interpretarNombre(Path.of("/data/blocks/aa/notas.txt"), BlockPaths.COMPLETO).isEmpty());
        assertTrue(paths.interpretarNombre(Path.of("/data/blocks/aa/x_1.blk"), BlockPaths.COMPLETO).isEmpty());
    }
}
