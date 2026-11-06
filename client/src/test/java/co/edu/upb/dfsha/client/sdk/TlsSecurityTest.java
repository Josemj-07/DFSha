package co.edu.upb.dfsha.client.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.upb.dfsha.client.config.ClientConfig;
import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import co.edu.upb.dfsha.client.sdk.model.ProgressListener;
import co.edu.upb.dfsha.client.simulator.SimCluster;
import co.edu.upb.dfsha.client.simulator.SimNode;
import co.edu.upb.dfsha.client.simulator.SimPki;
import co.edu.upb.dfsha.client.simulator.SimServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** El cliente debe rechazar cualquier extremo que no pruebe su identidad con TLS 1.3. */
class TlsSecurityTest {

    private static final int BLOCK = 1024 * 1024;

    @TempDir
    Path dir;

    private DfsClient crearCliente(String url, Path ca) {
        return DfsClientFactory.crear(new ClientConfig(url, ca, dir.resolve("home")), message -> { });
    }

    private void assertRechazaServidor(String url, Path ca) {
        try (DfsClient client = crearCliente(url, ca)) {
            var error = assertThrows(UnavailableException.class, () -> client.iniciarSesion("jose", "x".toCharArray()));
            assertEquals("TLS_REJECTED", error.obtenerCodigo());
        }
    }

    /** Con un nodo ilegítimo, el servidor responde pero ningún byte debe llegar a los nodos. */
    private void assertRechazaNodos(SimCluster cluster, Path ca) throws IOException {
        cluster.obtenerServidor().crearUsuario("jose", "clave");
        Path local = Files.write(dir.resolve("origen"), new byte[] {1, 2, 3});
        try (DfsClient client = crearCliente(cluster.obtenerServidor().obtenerUrl(), ca)) {
            client.iniciarSesion("jose", "clave".toCharArray());
            var error = assertThrows(UnavailableException.class,
                    () -> client.subir(local, "/home/jose/x", false, ProgressListener.NINGUNO));
            assertEquals("WRITE_FAILED", error.obtenerCodigo());
        }
    }

    @Test
    void conectaConLaCaCorrecta() throws Exception {
        try (SimCluster cluster = new SimCluster(dir.resolve("sim"), BLOCK)) {
            cluster.obtenerServidor().crearUsuario("jose", "clave");
            Path local = Files.write(dir.resolve("origen"), new byte[] {1, 2, 3});
            Path target = dir.resolve("destino");
            try (DfsClient client = crearCliente(cluster.obtenerServidor().obtenerUrl(), cluster.obtenerPki().obtenerCaCert())) {
                client.iniciarSesion("jose", "clave".toCharArray());
                client.subir(local, "/home/jose/x", false, ProgressListener.NINGUNO);
                client.descargar("/home/jose/x", target, ProgressListener.NINGUNO);
            }
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target));
        }
    }

    @Test
    void rechazaUnServidorDeOtraCa() throws Exception {
        try (SimCluster cluster = new SimCluster(dir.resolve("sim"), BLOCK)) {
            SimPki other = new SimPki(dir.resolve("otra"), "Atacante");
            assertRechazaServidor(cluster.obtenerServidor().obtenerUrl(), other.obtenerCaCert());
        }
    }

    @Test
    void rechazaUnServidorConOtroNombre() throws Exception {
        SimPki pki = new SimPki(dir.resolve("pki"), "CA");
        var identity = pki.emitir("server", "otro.example");
        try (SimServer server = new SimServer(pki.crearContextoServidor(identity), new String[] {"TLSv1.3"}, List.of(), BLOCK)) {
            assertRechazaServidor(server.obtenerUrl(), pki.obtenerCaCert());
        }
    }

    @Test
    void rechazaUnServidorQueSoloHablaTls12() throws Exception {
        SimPki pki = new SimPki(dir.resolve("pki"), "CA");
        var identity = pki.emitir("server", "localhost");
        try (SimServer server = new SimServer(pki.crearContextoServidor(identity), new String[] {"TLSv1.2"}, List.of(), BLOCK)) {
            assertRechazaServidor(server.obtenerUrl(), pki.obtenerCaCert());
        }
    }

    @Test
    void rechazaNodosDeOtraCa() throws Exception {
        SimPki other = new SimPki(dir.resolve("otra"), "Atacante");
        String[] names = {"localhost", "127.0.0.1"};
        try (SimCluster cluster = new SimCluster(dir.resolve("sim"), BLOCK, other, names, "TLSv1.3")) {
            assertRechazaNodos(cluster, cluster.obtenerPki().obtenerCaCert());
            assertSinBloques(cluster);
        }
    }

    @Test
    void rechazaNodosConOtroNombre() throws Exception {
        try (SimCluster cluster = new SimCluster(dir.resolve("sim"), BLOCK, null, new String[] {"otro.example"}, "TLSv1.3")) {
            assertRechazaNodos(cluster, cluster.obtenerPki().obtenerCaCert());
            assertSinBloques(cluster);
        }
    }

    @Test
    void rechazaNodosQueSoloHablanTls12() throws Exception {
        String[] names = {"localhost", "127.0.0.1"};
        try (SimCluster cluster = new SimCluster(dir.resolve("sim"), BLOCK, null, names, "TLSv1.2")) {
            assertRechazaNodos(cluster, cluster.obtenerPki().obtenerCaCert());
            assertSinBloques(cluster);
        }
    }

    private void assertSinBloques(SimCluster cluster) throws IOException {
        for (SimNode node : cluster.obtenerNodos()) {
            try (var files = Files.list(dir.resolve("sim").resolve(node.obtenerId()))) {
                assertFalse(files.findAny().isPresent(), "ningún byte debe llegar a " + node.obtenerId());
            }
        }
    }
}
