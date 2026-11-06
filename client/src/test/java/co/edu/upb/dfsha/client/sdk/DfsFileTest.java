package co.edu.upb.dfsha.client.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.upb.dfsha.client.sdk.error.ConflictException;
import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import co.edu.upb.dfsha.client.sdk.model.BlockRange;
import co.edu.upb.dfsha.client.sdk.model.LockMode;
import co.edu.upb.dfsha.client.sdk.model.OpenMode;
import co.edu.upb.dfsha.client.simulator.SimNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DfsFileTest extends IntegrationBase {

    private static final String FILE = "/home/jose/datos.bin";
    private static final Duration TTL = Duration.ofSeconds(30);

    private void escribir(DfsFile file, long offset, String text) {
        file.escribir(offset, ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String leer(DfsFile file, long offset, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        int read = file.leer(offset, buffer);
        return read < 0 ? null : new String(buffer.array(), 0, read, StandardCharsets.UTF_8);
    }

    private DfsFile abrirConLock(DfsClient owner, BlockRange range) {
        DfsFile file = owner.abrir(FILE, OpenMode.READ_WRITE, false);
        file.bloquear(LockMode.EXCLUSIVE, range, TTL, Duration.ZERO);
        return file;
    }

    @Test
    void escribirYPublicarEnModoW() throws IOException {
        try (DfsFile file = client.abrir(FILE, OpenMode.WRITE, false)) {
            escribir(file, 0, "hola");
            assertTrue(file.tieneCambiosPendientes());
            assertEquals(1, file.volcar());
            assertFalse(file.tieneCambiosPendientes());
        }
        assertArrayEquals("hola".getBytes(StandardCharsets.UTF_8), descargar(FILE));
    }

    @Test
    void abrirWYCerrarDejaUnArchivoVacio() throws IOException {
        subir(FILE, crearDatos(100, 1));
        client.abrir(FILE, OpenMode.WRITE, false).close();
        assertEquals(0, client.obtenerAtributos(FILE).size());
    }

    @Test
    void escribirNoEsVisibleHastaPublicar() throws IOException {
        subir(FILE, "aaaa".getBytes(StandardCharsets.UTF_8));
        DfsFile writer = abrirConLock(client, null);
        escribir(writer, 0, "bbbb");
        assertEquals("bbbb", leer(writer, 0, 4), "el escritor lee su propio buffer");
        assertArrayEquals("aaaa".getBytes(StandardCharsets.UTF_8), descargar(FILE));
        writer.close();
        assertArrayEquals("bbbb".getBytes(StandardCharsets.UTF_8), descargar(FILE));
    }

    @Test
    void unLectorAbiertoConservaSuVersion() throws IOException {
        subir(FILE, "viejo".getBytes(StandardCharsets.UTF_8));
        try (DfsFile reader = client.abrir(FILE, OpenMode.READ, false)) {
            subir(FILE, "nuevo".getBytes(StandardCharsets.UTF_8));
            assertEquals("viejo", leer(reader, 0, 5));
        }
    }

    @Test
    void copyOnWriteModificaSoloLoTocado() throws IOException {
        byte[] data = crearDatos(2 * BLOCK + 10, 2);
        subir(FILE, data);
        try (DfsFile file = abrirConLock(client, new BlockRange(1, 1))) {
            escribir(file, BLOCK + 100, "XYZ");
        }
        byte[] expected = data.clone();
        System.arraycopy("XYZ".getBytes(StandardCharsets.UTF_8), 0, expected, BLOCK + 100, 3);
        assertArrayEquals(expected, descargar(FILE));
    }

    @Test
    void unaEscrituraPuedeCruzarBloques() throws IOException {
        byte[] data = crearDatos(2 * BLOCK, 3);
        subir(FILE, data);
        byte[] patch = crearDatos(100, 4);
        try (DfsFile file = abrirConLock(client, new BlockRange(0, 1))) {
            file.escribir(BLOCK - 50, ByteBuffer.wrap(patch));
        }
        byte[] expected = data.clone();
        System.arraycopy(patch, 0, expected, BLOCK - 50, 100);
        assertArrayEquals(expected, descargar(FILE));
    }

    @Test
    void leerCruzaBloquesLimpiosYSucios() throws IOException {
        byte[] data = crearDatos(2 * BLOCK, 5);
        subir(FILE, data);
        try (DfsFile file = abrirConLock(client, null)) {
            escribir(file, BLOCK, "Q");
            ByteBuffer buffer = ByteBuffer.allocate(4);
            assertEquals(4, file.leer(BLOCK - 2, buffer));
            assertArrayEquals(new byte[] {data[BLOCK - 2], data[BLOCK - 1], 'Q', data[BLOCK + 1]}, buffer.array());
        }
    }

    @Test
    void anexarAgregaAlFinalEIgnoraElOffset() throws IOException {
        byte[] data = crearDatos(BLOCK + 3, 6);
        subir(FILE, data);
        try (DfsFile file = client.abrir(FILE, OpenMode.APPEND, false)) {
            escribir(file, 0, "fin");
            assertEquals(BLOCK + 6, file.obtenerTamanio());
        }
        byte[] result = descargar(FILE);
        assertArrayEquals(data, Arrays.copyOf(result, data.length));
        assertEquals("fin", new String(result, data.length, 3, StandardCharsets.UTF_8));
    }

    @Test
    void volcarDosVecesNoCreaOtraVersion() throws IOException {
        subir(FILE, new byte[] {1});
        try (DfsFile file = abrirConLock(client, null)) {
            escribir(file, 0, "z");
            long version = file.volcar();
            assertEquals(version, file.volcar());
            assertEquals(2, version);
        }
    }

    @Test
    void variosVolcadosPublicanVersionesSucesivas() throws IOException {
        try (DfsFile file = client.abrir(FILE, OpenMode.WRITE, false)) {
            escribir(file, 0, "uno");
            assertEquals(1, file.volcar());
            escribir(file, 3, "dos");
            assertEquals(2, file.volcar());
            assertEquals("unodos", leer(file, 0, 6));
        }
        assertArrayEquals("unodos".getBytes(StandardCharsets.UTF_8), descargar(FILE));
    }

    @Test
    void elModoLecturaRechazaEscribir() throws IOException {
        subir(FILE, new byte[] {1});
        try (DfsFile file = client.abrir(FILE, OpenMode.READ, false)) {
            assertThrows(InvalidOperationException.class, () -> escribir(file, 0, "x"));
        }
    }

    @Test
    void noSeAdmitenHuecos() throws IOException {
        subir(FILE, new byte[] {1});
        DfsFile file = abrirConLock(client, null);
        assertThrows(InvalidOperationException.class, () -> escribir(file, 5, "x"));
        file.close();
    }

    @Test
    void rwSinLockSeRechazaAntesDeSubir() throws IOException {
        subir(FILE, new byte[] {1});
        DfsFile file = client.abrir(FILE, OpenMode.READ_WRITE, false);
        escribir(file, 0, "x");
        var error = assertThrows(InvalidOperationException.class, file::volcar);
        assertEquals("LOCK_REQUIRED", error.obtenerCodigo());
        assertThrows(InvalidOperationException.class, file::close);
    }

    @Test
    void unLockCompartidoNoAutorizaEscribir() throws IOException {
        subir(FILE, new byte[] {1});
        DfsFile file = client.abrir(FILE, OpenMode.READ_WRITE, false);
        file.bloquear(LockMode.SHARED, null, TTL, Duration.ZERO);
        escribir(file, 0, "x");
        assertThrows(InvalidOperationException.class, file::volcar);
    }

    @Test
    void unConflictoDeVersionConservaLosCambios() throws IOException {
        subir(FILE, crearDatos(10, 7));
        DfsClient other = crearCliente();
        DfsFile first = other.abrir(FILE, OpenMode.READ_WRITE, false);
        DfsFile second = client.abrir(FILE, OpenMode.READ_WRITE, false);
        var lock = first.bloquear(LockMode.EXCLUSIVE, null, TTL, Duration.ZERO);
        escribir(first, 0, "a");
        first.volcar();
        first.desbloquear(lock.lockId());
        second.bloquear(LockMode.EXCLUSIVE, null, TTL, Duration.ZERO);
        escribir(second, 0, "b");
        var error = assertThrows(ConflictException.class, second::volcar);
        assertEquals("VERSION_CONFLICT", error.obtenerCodigo());
        assertTrue(second.tieneCambiosPendientes());
        assertEquals("b", leer(second, 0, 1));
    }

    @Test
    void escritoresEnRangosDisjuntosPublicanAmbos() throws IOException {
        byte[] data = crearDatos(2 * BLOCK, 8);
        subir(FILE, data);
        DfsClient other = crearCliente();
        DfsFile first = abrirConLock(client, new BlockRange(0, 0));
        DfsFile second = abrirConLock(other, new BlockRange(1, 1));
        escribir(first, 0, "AA");
        escribir(second, BLOCK, "BB");
        first.close();
        second.close();
        byte[] expected = data.clone();
        expected[0] = 'A';
        expected[1] = 'A';
        expected[BLOCK] = 'B';
        expected[BLOCK + 1] = 'B';
        assertArrayEquals(expected, descargar(FILE));
    }

    @Test
    void unLockExclusivoChocaConOtro() throws IOException {
        subir(FILE, new byte[] {1});
        DfsClient other = crearCliente();
        abrirConLock(client, null);
        DfsFile second = other.abrir(FILE, OpenMode.READ_WRITE, false);
        var error = assertThrows(ConflictException.class,
                () -> second.bloquear(LockMode.EXCLUSIVE, null, TTL, Duration.ZERO));
        assertEquals("LOCK_CONFLICT", error.obtenerCodigo());
    }

    @Test
    void cerrarLiberaLosLocks() throws IOException {
        subir(FILE, new byte[] {1});
        abrirConLock(client, null).close();
        abrirConLock(crearCliente(), null).close();
    }

    @Test
    void cerrarBorraElBufferLocal() throws IOException {
        subir(FILE, new byte[] {1});
        try (DfsFile file = abrirConLock(client, null)) {
            escribir(file, 0, "x");
        }
        try (var entries = Files.walk(dir.resolve("home-1/tmp"))) {
            assertEquals(1, entries.count(), "solo debe quedar el directorio raíz");
        }
    }

    @Test
    void leerAlFinalDevuelveMenosUno() throws IOException {
        subir(FILE, new byte[] {1, 2});
        try (DfsFile file = client.abrir(FILE, OpenMode.READ, false)) {
            assertEquals(null, leer(file, 2, 1));
            assertEquals(2, file.obtenerTamanio());
        }
    }

    @Test
    void cerrarDosVecesNoFalla() throws IOException {
        subir(FILE, new byte[] {1});
        DfsFile file = client.abrir(FILE, OpenMode.READ, false);
        file.close();
        file.close();
        assertThrows(InvalidOperationException.class, () -> leer(file, 0, 1));
    }

    @Test
    void unaLecturaParcialSobreviveAUnaReplicaCaida() throws Exception {
        byte[] data = crearDatos(BLOCK, 9);
        subir(FILE, data);
        try (DfsFile file = client.abrir(FILE, OpenMode.READ, false)) {
            cluster.obtenerServidor().obtenerReplicas(FILE, 0).get(0).detener();
            ByteBuffer buffer = ByteBuffer.allocate(16);
            assertEquals(16, file.leer(100, buffer));
            assertArrayEquals(Arrays.copyOfRange(data, 100, 116), buffer.array());
        }
    }

    @Test
    void copyOnWriteFallidoNoDejaUnBloqueVacioEnElBuffer() throws Exception {
        subir(FILE, crearDatos(BLOCK, 10));
        DfsFile file = abrirConLock(client, null);
        for (SimNode node : cluster.obtenerNodos()) {
            node.detener();
        }
        assertThrows(RuntimeException.class, () -> escribir(file, 5, "x"));
        assertFalse(file.tieneCambiosPendientes());
    }
}
