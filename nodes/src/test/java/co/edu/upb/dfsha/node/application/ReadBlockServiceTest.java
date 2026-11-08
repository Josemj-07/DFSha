package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.domain.Op;
import co.edu.upb.dfsha.node.domain.PermissionDeniedException;
import co.edu.upb.dfsha.node.port.BlockWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadBlockServiceTest {

    private static final BlockKey BLOQUE = new BlockKey("blk_01", 1);
    private static final byte[] DATOS = "0123456789".getBytes();

    @TempDir
    Path dataDir;

    private FileSystemBlockStore store;
    private ReadBlockService servicio;
    private final BlockGrant lector = new BlockGrant("jose", Set.of(Op.READ), Set.of("blk_01:1"));

    @BeforeEach
    void preparar() throws NoSuchAlgorithmException {
        store = new FileSystemBlockStore(new BlockPaths(dataDir), new MetaFileCodec(), 0);
        BlockWriter writer = store.crear(BLOQUE, DATOS.length);
        writer.escribir(ByteBuffer.wrap(DATOS));
        writer.confirmar(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(DATOS)));
        servicio = new ReadBlockService(store);
    }

    @Test
    void conLength0LeeHastaElFinal() throws IOException {
        try (BlockRead lectura = servicio.abrir(lector, BLOQUE, 4, 0)) {
            assertEquals(6, lectura.length());
            assertArrayEquals("456789".getBytes(), Channels.newInputStream(lectura.canal()).readAllBytes());
        }
    }

    @Test
    void leeUnRangoExacto() {
        try (BlockRead lectura = servicio.abrir(lector, BLOQUE, 2, 3)) {
            assertEquals(2, lectura.offset());
            assertEquals(3, lectura.length());
        }
    }

    @Test
    void unRangoVacioAlFinalEsValido() {
        try (BlockRead lectura = servicio.abrir(lector, BLOQUE, 10, 0)) {
            assertEquals(0, lectura.length());
        }
    }

    @Test
    void rechazaRangosFueraDelBloque() {
        assertThrows(IllegalArgumentException.class, () -> servicio.abrir(lector, BLOQUE, 11, 0));
        assertThrows(IllegalArgumentException.class, () -> servicio.abrir(lector, BLOQUE, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> servicio.abrir(lector, BLOQUE, 5, 6));
        assertThrows(IllegalArgumentException.class, () -> servicio.abrir(lector, BLOQUE, 0, -1));
    }

    @Test
    void sinPermisoDeLecturaNoAbreNada() {
        var soloEscritura = new BlockGrant("jose", Set.of(Op.WRITE), Set.of("blk_01:1"));

        assertThrows(PermissionDeniedException.class, () -> servicio.abrir(soloEscritura, BLOQUE, 0, 0));
    }

    @Test
    void unTokenDeOtroBloqueNoSirve() {
        var otro = new BlockGrant("jose", Set.of(Op.READ), Set.of("blk_02:1"));

        assertThrows(PermissionDeniedException.class, () -> servicio.abrir(otro, BLOQUE, 0, 0));
    }

    @Test
    void otraGeneracionNoExiste() {
        var gen2 = new BlockKey("blk_01", 2);
        var permiso = new BlockGrant("jose", Set.of(Op.READ), Set.of("blk_01:2"));

        assertThrows(BlockNotFoundException.class, () -> servicio.abrir(permiso, gen2, 0, 0));
    }

    @Test
    void unBloqueEnCuarentenaNoSeLee() {
        store.ponerEnCuarentena(BLOQUE);

        assertThrows(BlockNotFoundException.class, () -> servicio.abrir(lector, BLOQUE, 0, 0));
    }
}
