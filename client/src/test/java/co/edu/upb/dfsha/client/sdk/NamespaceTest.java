package co.edu.upb.dfsha.client.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.upb.dfsha.client.sdk.error.ConflictException;
import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import co.edu.upb.dfsha.client.sdk.error.NotAuthenticatedException;
import co.edu.upb.dfsha.client.sdk.error.NotFoundException;
import co.edu.upb.dfsha.client.sdk.model.FileInfo;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class NamespaceTest extends IntegrationBase {

    @Test
    void lasRutasRelativasUsanElDirectorioActual() throws IOException {
        client.crearDirectorio("a/b", true);
        client.cambiarDirectorio("a");
        assertEquals("/home/jose/a", client.obtenerDirectorioActual());
        subir("dentro.txt", new byte[] {1});
        assertEquals(1, client.obtenerAtributos("/home/jose/a/dentro.txt").size());
        client.cambiarDirectorio("..");
        assertEquals("/home/jose", client.obtenerDirectorioActual());
    }

    @Test
    void cdSoloEntraADirectorios() throws IOException {
        subir("archivo", new byte[] {1});
        assertThrows(InvalidOperationException.class, () -> client.cambiarDirectorio("archivo"));
        assertThrows(NotFoundException.class, () -> client.cambiarDirectorio("no-existe"));
    }

    @Test
    void elListadoRecorreTodasLasPaginas() {
        cluster.obtenerServidor().establecerTamanioPagina(2);
        for (int i = 0; i < 5; i++) {
            client.crearDirectorio("d" + i, false);
        }
        List<String> names = client.listar(".").stream().map(FileInfo::name).sorted().toList();
        assertEquals(List.of("d0", "d1", "d2", "d3", "d4"), names);
    }

    @Test
    void mkdirYRmdirRespetanSusReglas() throws IOException {
        client.crearDirectorio("d", false);
        assertThrows(ConflictException.class, () -> client.crearDirectorio("d", false));
        subir("d/x", new byte[] {1});
        assertThrows(ConflictException.class, () -> client.borrarDirectorio("d", false));
        client.borrarDirectorio("d", true);
        assertThrows(NotFoundException.class, () -> client.obtenerAtributos("d"));
    }

    @Test
    void cpYMvConservanLosDatos() throws IOException {
        byte[] data = crearDatos(BLOCK + 1, 1);
        subir("a", data);
        client.copiar("a", "b");
        client.mover("a", "c");
        assertArrayEquals(data, descargar("b"));
        assertArrayEquals(data, descargar("c"));
        assertThrows(NotFoundException.class, () -> client.obtenerAtributos("a"));
    }

    @Test
    void rutasConEspaciosYAcentosViajanCodificadas() throws IOException {
        byte[] data = {1, 2, 3};
        subir("año 2026 & más.txt", data);
        assertArrayEquals(data, descargar("año 2026 & más.txt"));
        assertTrue(client.listar(".").stream().anyMatch(file -> file.name().equals("año 2026 & más.txt")));
    }

    @Test
    void renuevaLaSesionVencidaSinQueElUsuarioLoNote() {
        cluster.obtenerServidor().vencerAccesos();
        assertEquals(0, client.listar(".").size());
    }

    @Test
    void sinSesionPideIniciarla() {
        client.cerrarSesion();
        assertThrows(NotAuthenticatedException.class, () -> client.listar("."));
    }

    @Test
    void despuesDelModoSeguroSigueFuncionando() throws IOException {
        cluster.obtenerServidor().simularModoSeguro(1);
        byte[] data = {7};
        subir("a", data);
        assertArrayEquals(data, descargar("a"));
    }
}
