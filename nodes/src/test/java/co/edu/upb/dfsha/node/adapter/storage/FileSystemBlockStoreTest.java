package co.edu.upb.dfsha.node.adapter.storage;

import co.edu.upb.dfsha.node.domain.BlockAlreadyExistsException;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.domain.BlockWriteInProgressException;
import co.edu.upb.dfsha.node.domain.DataIntegrityException;
import co.edu.upb.dfsha.node.domain.InsufficientSpaceException;
import co.edu.upb.dfsha.node.port.BlockWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;

import static co.edu.upb.dfsha.node.adapter.storage.StoreFixtures.calcularSha256;
import static co.edu.upb.dfsha.node.adapter.storage.StoreFixtures.crearDatos;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemBlockStoreTest {

    private static final int TRES_MIB = 3 * 1024 * 1024;

    @TempDir
    Path dataDir;

    private BlockPaths paths;
    private FileSystemBlockStore store;
    private final BlockKey bloque = new BlockKey("blk_3f2a-01", 1);

    @BeforeEach
    void preparar() {
        paths = new BlockPaths(dataDir);
        store = new FileSystemBlockStore(paths, new MetaFileCodec(), 0);
    }

    @Test
    void publicaElBloqueSoloAlConfirmar() {
        byte[] datos = crearDatos(TRES_MIB);
        BlockWriter writer = store.crear(bloque, datos.length);
        escribirEnTrozos(writer, datos);

        assertTrue(Files.exists(paths.obtenerParcial(bloque)));
        assertFalse(Files.exists(paths.obtenerCompleto(bloque)));

        BlockMeta meta = writer.confirmar(calcularSha256(datos));

        assertFalse(Files.exists(paths.obtenerParcial(bloque)));
        assertTrue(Files.exists(paths.obtenerCompleto(bloque)));
        assertEquals(meta, store.buscar(bloque).orElseThrow());
    }

    @Test
    void leeLoMismoQueSeEscribio() throws IOException {
        byte[] datos = crearDatos(TRES_MIB);
        guardar(datos);

        try (InputStream entrada = Channels.newInputStream(store.abrir(bloque, 0))) {
            assertArrayEquals(datos, entrada.readAllBytes());
        }
    }

    @Test
    void leeDesdeUnOffset() throws IOException {
        byte[] datos = crearDatos(1000);
        guardar(datos);

        try (InputStream entrada = Channels.newInputStream(store.abrir(bloque, 600))) {
            byte[] resto = entrada.readAllBytes();
            assertEquals(400, resto.length);
            assertEquals(datos[600], resto[0]);
        }
    }

    @Test
    void unHashIncorrectoNoDejaNiBlkNiPart() {
        byte[] datos = crearDatos(4096);
        BlockWriter writer = store.crear(bloque, datos.length);
        writer.escribir(ByteBuffer.wrap(datos));

        assertThrows(DataIntegrityException.class, () -> writer.confirmar(calcularSha256(new byte[1])));

        assertFalse(Files.exists(paths.obtenerCompleto(bloque)));
        assertFalse(Files.exists(paths.obtenerParcial(bloque)));
        assertFalse(Files.exists(paths.obtenerMeta(bloque)));
    }

    @Test
    void rechazaUnBloqueIncompleto() {
        byte[] datos = crearDatos(4096);
        BlockWriter writer = store.crear(bloque, datos.length + 1);
        writer.escribir(ByteBuffer.wrap(datos));

        assertThrows(DataIntegrityException.class, () -> writer.confirmar(calcularSha256(datos)));
        assertFalse(Files.exists(paths.obtenerCompleto(bloque)));
    }

    @Test
    void rechazaMasDatosDeLosDeclarados() {
        BlockWriter writer = store.crear(bloque, 10);

        assertThrows(DataIntegrityException.class, () -> writer.escribir(ByteBuffer.wrap(new byte[11])));
    }

    @Test
    void unBloqueCompletoNoSeReescribe() {
        guardar(crearDatos(100));

        assertThrows(BlockAlreadyExistsException.class, () -> store.crear(bloque, 100));
    }

    @Test
    void dosEscriturasDelMismoBloqueNoSeMezclan() {
        try (BlockWriter primera = store.crear(bloque, 100)) {
            assertThrows(BlockWriteInProgressException.class, () -> store.crear(bloque, 100));
        }
    }

    @Test
    void cerrarSinConfirmarBorraElPart() {
        try (BlockWriter writer = store.crear(bloque, 100)) {
            writer.escribir(ByteBuffer.wrap(new byte[50]));
        }

        assertFalse(Files.exists(paths.obtenerParcial(bloque)));
        assertTrue(store.buscar(bloque).isEmpty());
    }

    @Test
    void otraGeneracionEsOtroBloque() {
        guardar(crearDatos(100));
        var siguiente = new BlockKey(bloque.blockId(), 2);

        assertTrue(store.buscar(siguiente).isEmpty());
        assertThrows(BlockNotFoundException.class, () -> store.abrir(siguiente, 0));
    }

    @Test
    void rechazaEscribirSinEspacio() {
        var sinEspacio = new FileSystemBlockStore(paths, new MetaFileCodec(), Long.MAX_VALUE / 2);

        assertThrows(InsufficientSpaceException.class, () -> sinEspacio.crear(bloque, 100));
        assertFalse(Files.exists(paths.obtenerParcial(bloque)));
    }

    @Test
    void borrarEsIdempotente() {
        guardar(crearDatos(100));

        store.borrar(bloque);
        store.borrar(bloque);

        assertTrue(store.buscar(bloque).isEmpty());
        assertFalse(Files.exists(paths.obtenerMeta(bloque)));
    }

    @Test
    void unBloqueEnCuarentenaYaNoSeLee() {
        guardar(crearDatos(100));

        store.ponerEnCuarentena(bloque);

        assertThrows(BlockNotFoundException.class, () -> store.abrir(bloque, 0));
        assertTrue(Files.exists(paths.obtenerCorrupto(bloque)));
        assertTrue(store.listar().isEmpty());
    }

    @Test
    void listaSoloLosBloquesCompletos() {
        guardar(crearDatos(100));
        var otro = new BlockKey("blk_99", 4);
        byte[] datosOtro = crearDatos(50);
        BlockWriter writer = store.crear(otro, datosOtro.length);
        writer.escribir(ByteBuffer.wrap(datosOtro));
        writer.confirmar(calcularSha256(datosOtro));

        try (BlockWriter enCurso = store.crear(new BlockKey("blk_77", 1), 10)) {
            assertEquals(2, store.listar().size());
        }
    }

    private void guardar(byte[] datos) {
        BlockWriter writer = store.crear(bloque, datos.length);
        writer.escribir(ByteBuffer.wrap(datos));
        writer.confirmar(calcularSha256(datos));
    }

    // Igual que por gRPC: el bloque llega en chunks de 1 MiB.
    private static void escribirEnTrozos(BlockWriter writer, byte[] datos) {
        int chunk = 1024 * 1024;
        for (int offset = 0; offset < datos.length; offset += chunk) {
            writer.escribir(ByteBuffer.wrap(datos, offset, Math.min(chunk, datos.length - offset)));
        }
    }
}
