package co.edu.upb.dfsha.client.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.upb.dfsha.client.sdk.error.ConflictException;
import co.edu.upb.dfsha.client.sdk.error.NotFoundException;
import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import co.edu.upb.dfsha.client.sdk.model.ProgressListener;
import co.edu.upb.dfsha.client.simulator.SimNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransferTest extends IntegrationBase {

    private static final String FILE = "/home/jose/archivo.bin";

    @ParameterizedTest
    @ValueSource(ints = {0, 1, BLOCK - 1, BLOCK, BLOCK + 1, 2 * BLOCK, 3 * BLOCK + 5})
    void idaYVueltaConservaLosBytes(int size) throws IOException {
        byte[] data = crearDatos(size, size);
        assertEquals(1, subir(FILE, data));
        assertEquals(size, client.obtenerAtributos(FILE).size());
        assertArrayEquals(data, descargar(FILE));
    }

    @Test
    void cadaBloqueQuedaEnDosNodosDistintos() throws IOException {
        subir(FILE, crearDatos(3 * BLOCK, 1));
        for (int idx = 0; idx < 3; idx++) {
            List<SimNode> replicas = cluster.obtenerServidor().obtenerReplicas(FILE, idx);
            assertEquals(2, replicas.size());
            assertFalse(replicas.get(0).obtenerId().equals(replicas.get(1).obtenerId()));
        }
    }

    @Test
    void informaElAvance() throws IOException {
        AtomicInteger last = new AtomicInteger();
        client.subir(escribirLocal("a", crearDatos(2 * BLOCK, 2)), FILE, false, (done, total) -> last.set(done));
        assertEquals(2, last.get());
    }

    @Test
    void conBarraFinalUsaElNombreLocal() throws IOException {
        client.subir(escribirLocal("nombre.txt", new byte[] {1}), "/home/jose/", false, ProgressListener.NINGUNO);
        assertEquals(1, client.obtenerAtributos("/home/jose/nombre.txt").size());
    }

    @Test
    void unArchivoLocalInexistenteNoAbreNada() {
        assertThrows(NotFoundException.class,
                () -> client.subir(dir.resolve("no-existe"), FILE, false, ProgressListener.NINGUNO));
    }

    @Test
    void exclusivoFallaSiYaExiste() throws IOException {
        subir(FILE, new byte[] {1});
        assertThrows(ConflictException.class,
                () -> client.subir(escribirLocal("b", new byte[] {2}), FILE, true, ProgressListener.NINGUNO));
    }

    @Test
    void sobreescribirCreaOtraVersion() throws IOException {
        subir(FILE, crearDatos(BLOCK + 1, 3));
        byte[] second = crearDatos(10, 4);
        assertEquals(2, subir(FILE, second));
        assertArrayEquals(second, descargar(FILE));
    }

    @Test
    void siCaeUnNodoLaEscrituraSigueConLosOtrosDos() throws Exception {
        cluster.obtenerNodo(1).detener();
        byte[] data = crearDatos(3 * BLOCK, 5);
        subir(FILE, data);
        assertArrayEquals(data, descargar(FILE));
    }

    @Test
    void siFallaElSegundoNodoLaCadenaSeRecupera() throws IOException {
        cluster.obtenerNodo(2).inyectar(SimNode.Failure.ABORT_WRITE, 1);
        byte[] data = crearDatos(3 * BLOCK, 6);
        subir(FILE, data);
        assertArrayEquals(data, descargar(FILE));
    }

    @Test
    void reenviaUnBloqueSiFaltaUnRecibo() throws IOException {
        cluster.obtenerNodo(1).inyectar(SimNode.Failure.OMIT_RECEIPT, 1);
        byte[] data = crearDatos(BLOCK, 7);
        subir(FILE, data);
        assertArrayEquals(data, descargar(FILE));
    }

    @Test
    void agotadosLosIntentosNoPublicaNada() throws IOException {
        byte[] original = crearDatos(10, 8);
        subir(FILE, original);
        cluster.obtenerNodos().forEach(node -> node.inyectar(SimNode.Failure.ABORT_WRITE, 1000));
        assertThrows(UnavailableException.class, () -> subir(FILE, crearDatos(BLOCK, 9)));
        cluster.obtenerNodos().forEach(node -> node.inyectar(SimNode.Failure.NONE, 0));
        assertEquals(1, client.obtenerAtributos(FILE).version());
        assertArrayEquals(original, descargar(FILE));
    }

    @Test
    void siElServidorPerdioElHandleNoPublica() throws IOException {
        Path local = escribirLocal("c", crearDatos(BLOCK, 10));
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(ConflictException.class, () -> client.subir(local, FILE, false, (done, total) -> {
            if (calls.incrementAndGet() == 1) {
                cluster.obtenerServidor().simularPerdidaDeHandles();
            }
        }));
        assertEquals("HANDLE_EXPIRED", error.obtenerCodigo());
    }

    @Test
    void leeDeLaOtraReplicaSiLaPrimeraEstaCaida() throws Exception {
        byte[] data = crearDatos(BLOCK, 11);
        subir(FILE, data);
        cluster.obtenerServidor().obtenerReplicas(FILE, 0).get(0).detener();
        assertArrayEquals(data, descargar(FILE));
        assertReporte("UNREACHABLE");
    }

    @Test
    void detectaYEvitaUnaReplicaCorrupta() throws IOException {
        byte[] data = crearDatos(BLOCK, 12);
        subir(FILE, data);
        cluster.obtenerServidor().obtenerReplicas(FILE, 0).get(0).inyectar(SimNode.Failure.CORRUPT_READ, 1);
        assertArrayEquals(data, descargar(FILE));
        assertReporte("CORRUPT");
    }

    @Test
    void detectaCrcIncorrecto() throws IOException {
        byte[] data = crearDatos(BLOCK, 13);
        subir(FILE, data);
        cluster.obtenerServidor().obtenerReplicas(FILE, 0).get(0).inyectar(SimNode.Failure.BAD_CRC_READ, 1);
        assertArrayEquals(data, descargar(FILE));
        assertReporte("CORRUPT");
    }

    @Test
    void unaReplicaColgadaNoDetieneLaDescarga() throws IOException {
        byte[] data = crearDatos(BLOCK, 14);
        subir(FILE, data);
        cluster.obtenerServidor().obtenerReplicas(FILE, 0).get(0).inyectar(SimNode.Failure.HANG_READ, 1);
        assertArrayEquals(data, descargar(FILE));
    }

    @Test
    void sinReplicasFallaYNoDejaTemporales() throws Exception {
        subir(FILE, crearDatos(BLOCK, 15));
        for (SimNode node : cluster.obtenerNodos()) {
            node.detener();
        }
        Path target = dir.resolve("salida.bin");
        var error = assertThrows(UnavailableException.class,
                () -> client.descargar(FILE, target, ProgressListener.NINGUNO));
        assertEquals("NO_REPLICAS", error.obtenerCodigo());
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("salida.bin.dfspart")));
    }

    @Test
    void unaDescargaFallidaNoTocaElDestinoExistente() throws Exception {
        subir(FILE, crearDatos(BLOCK, 16));
        Path target = escribirLocal("existente.bin", new byte[] {42});
        for (SimNode node : cluster.obtenerNodos()) {
            node.detener();
        }
        assertThrows(UnavailableException.class, () -> client.descargar(FILE, target, ProgressListener.NINGUNO));
        assertArrayEquals(new byte[] {42}, Files.readAllBytes(target));
    }

    @Test
    void descargarEnUnDirectorioUsaElNombreRemoto() throws IOException {
        subir(FILE, new byte[] {1, 2});
        Path folder = Files.createDirectory(dir.resolve("carpeta"));
        client.descargar(FILE, folder, ProgressListener.NINGUNO);
        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(folder.resolve("archivo.bin")));
    }

    private void assertReporte(String reason) {
        List<Map<String, Object>> reports = cluster.obtenerServidor().obtenerReportes();
        assertTrue(reports.stream().anyMatch(report -> reason.equals(report.get("reason"))),
                "reportes: " + reports);
    }
}
