package co.edu.upb.dfsha.node.adapter.storage;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.port.BlockWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static co.edu.upb.dfsha.node.adapter.storage.StoreFixtures.calcularSha256;
import static co.edu.upb.dfsha.node.adapter.storage.StoreFixtures.crearDatos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStoreRecoveryTest {

    private static final Instant AHORA = Instant.parse("2026-11-07T12:00:00Z");

    @TempDir
    Path dataDir;

    private BlockPaths paths;
    private FileSystemBlockStore store;
    private BlockStoreRecovery recovery;

    @BeforeEach
    void preparar() {
        paths = new BlockPaths(dataDir);
        store = new FileSystemBlockStore(paths, new MetaFileCodec(), 0);
        recovery = new BlockStoreRecovery(store, paths, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void borraLasEscriturasInterrumpidasYConservaLosBloquesBuenos() throws IOException {
        var bueno = guardar(new BlockKey("blk_aa", 1), 200);
        var interrumpido = new BlockKey("blk_bb", 1);
        simularCaidaDuranteEscritura(interrumpido);

        RecoveryReport reporte = recovery.recuperar();

        assertEquals(new RecoveryReport(1, 1, 0, 0), reporte);
        assertFalse(Files.exists(paths.obtenerParcial(interrumpido)));
        assertTrue(store.buscar(bueno).isPresent());
    }

    @Test
    void ponEnCuarentenaUnBlkSinMeta() throws IOException {
        var key = guardar(new BlockKey("blk_cc", 1), 200);
        Files.delete(paths.obtenerMeta(key));

        RecoveryReport reporte = recovery.recuperar();

        assertEquals(1, reporte.enCuarentena());
        assertTrue(Files.exists(paths.obtenerCorrupto(key)));
        assertFalse(Files.exists(paths.obtenerCompleto(key)));
    }

    @Test
    void ponEnCuarentenaUnBlkTruncado() throws IOException {
        var key = guardar(new BlockKey("blk_dd", 1), 200);
        Files.write(paths.obtenerCompleto(key), new byte[10]);

        assertEquals(1, recovery.recuperar().enCuarentena());
        assertTrue(store.buscar(key).isEmpty());
    }

    @Test
    void borraMetasSinBloque() throws IOException {
        var key = new BlockKey("blk_ee", 1);
        Files.createDirectories(paths.obtenerDirectorio(key));
        Files.writeString(paths.obtenerMeta(key), "{}");

        assertEquals(1, recovery.recuperar().metasHuerfanas());
        assertFalse(Files.exists(paths.obtenerMeta(key)));
    }

    @Test
    void limpiaSoloLosPartAbandonados() throws IOException {
        var viejo = new BlockKey("blk_ff", 1);
        var reciente = new BlockKey("blk_gg", 1);
        simularCaidaDuranteEscritura(viejo);
        simularCaidaDuranteEscritura(reciente);
        Files.setLastModifiedTime(paths.obtenerParcial(viejo), FileTime.from(AHORA.minus(Duration.ofHours(2))));
        Files.setLastModifiedTime(paths.obtenerParcial(reciente), FileTime.from(AHORA.minus(Duration.ofMinutes(5))));

        int borradas = recovery.limpiarPartesAbandonadas(Duration.ofHours(1));

        assertEquals(1, borradas);
        assertFalse(Files.exists(paths.obtenerParcial(viejo)));
        assertTrue(Files.exists(paths.obtenerParcial(reciente)));
    }

    private BlockKey guardar(BlockKey key, int tamanio) {
        byte[] datos = crearDatos(tamanio);
        BlockWriter writer = store.crear(key, datos.length);
        writer.escribir(ByteBuffer.wrap(datos));
        writer.confirmar(calcularSha256(datos));
        return key;
    }

    // Un kill -9 deja el .part escrito a medias y sin cerrar: no se llama a confirmar ni a abortar.
    private void simularCaidaDuranteEscritura(BlockKey key) {
        BlockWriter writer = store.crear(key, 1000);
        writer.escribir(ByteBuffer.wrap(new byte[300]));
    }
}
