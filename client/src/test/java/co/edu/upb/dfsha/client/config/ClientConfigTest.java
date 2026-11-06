package co.edu.upb.dfsha.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigTest {

    @TempDir
    Path home;

    private void escribirArchivo() throws IOException {
        Files.createDirectories(home.resolve(".dfs"));
        Files.writeString(home.resolve(".dfs/config.properties"), "server.url=https://archivo:1\nca.cert=/archivo/ca.crt\n");
    }

    @Test
    void lasOpcionesGananALasVariablesYAlArchivo() throws IOException {
        escribirArchivo();
        var env = Map.of("DFS_SERVER_URL", "https://variable:1", "DFS_CA_CERT", "/variable/ca.crt");
        var config = ClientConfig.cargar("https://opcion:1", "/opcion/ca.crt", env, home.toString());
        assertEquals("https://opcion:1", config.serverUrl());
        assertEquals(Path.of("/opcion/ca.crt"), config.caCert());
    }

    @Test
    void lasVariablesGananAlArchivo() throws IOException {
        escribirArchivo();
        var env = Map.of("DFS_SERVER_URL", "https://variable:1");
        var config = ClientConfig.cargar(null, null, env, home.toString());
        assertEquals("https://variable:1", config.serverUrl());
        assertEquals(Path.of("/archivo/ca.crt"), config.caCert());
    }

    @Test
    void rechazaUrlSinTls() {
        assertThrows(InvalidOperationException.class,
                () -> ClientConfig.cargar("http://servidor:8443", "/ca.crt", Map.of(), home.toString()));
    }

    @Test
    void exigeServidorYCertificado() {
        assertThrows(InvalidOperationException.class, () -> ClientConfig.cargar(null, "/ca.crt", Map.of(), home.toString()));
        assertThrows(InvalidOperationException.class,
                () -> ClientConfig.cargar("https://s:1", null, Map.of(), home.toString()));
    }

    @Test
    void quitaLaBarraFinalDeLaUrl() {
        assertEquals("https://s:1", ClientConfig.cargar("https://s:1/", "/ca", Map.of(), home.toString()).serverUrl());
    }
}
