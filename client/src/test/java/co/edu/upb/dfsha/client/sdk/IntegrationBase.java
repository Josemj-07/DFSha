package co.edu.upb.dfsha.client.sdk;

import co.edu.upb.dfsha.client.config.ClientConfig;
import co.edu.upb.dfsha.client.sdk.model.ProgressListener;
import co.edu.upb.dfsha.client.simulator.SimCluster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Base de las pruebas de integración: cliente real contra el simulador, con TLS real. */
abstract class IntegrationBase {

    protected static final int BLOCK = 4 * 1024 * 1024;
    protected static final String USER = "jose";

    @TempDir
    protected Path dir;

    protected SimCluster cluster;
    protected DfsClient client;
    private final List<DfsClient> clients = new ArrayList<>();
    private int homes;

    @BeforeEach
    void iniciarCluster() throws Exception {
        cluster = new SimCluster(dir.resolve("sim"), BLOCK);
        cluster.obtenerServidor().crearUsuario(USER, "clave");
        client = crearCliente();
    }

    @AfterEach
    void detenerCluster() throws Exception {
        clients.forEach(DfsClient::close);
        cluster.close();
    }

    /** Crea un cliente con sesión propia, como otra máquina. */
    protected DfsClient crearCliente() {
        Path home = dir.resolve("home-" + (++homes));
        var config = new ClientConfig(cluster.obtenerServidor().obtenerUrl(), cluster.obtenerPki().obtenerCaCert(), home);
        DfsClient created = DfsClientFactory.crear(config, message -> { });
        clients.add(created);
        created.iniciarSesion(USER, "clave".toCharArray());
        return created;
    }

    protected byte[] crearDatos(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    protected Path escribirLocal(String name, byte[] data) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, data);
        return file;
    }

    protected long subir(String remote, byte[] data) throws IOException {
        return client.subir(escribirLocal("origen-" + System.nanoTime(), data), remote, false, ProgressListener.NINGUNO);
    }

    protected byte[] descargar(String remote) throws IOException {
        Path target = dir.resolve("destino-" + System.nanoTime());
        client.descargar(remote, target, ProgressListener.NINGUNO);
        return Files.readAllBytes(target);
    }
}
