package co.edu.upb.dfsha.client.simulator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Servidor y tres nodos simulados con una CA propia. */
public final class SimCluster implements AutoCloseable {

    private final SimPki pki;
    private final List<SimNode> nodes = new ArrayList<>();
    private final SimServer server;

    public SimCluster(Path dir, long blockSize) throws Exception {
        this(dir, blockSize, null, new String[] {"localhost", "127.0.0.1"}, "TLSv1.3");
    }

    /**
     * @param nodePki CA que firma los nodos; una distinta simula nodos ilegítimos. Si es nula, la del servidor
     * @param nodeSans nombres del certificado de los nodos
     * @param nodeTls versión de TLS de los nodos
     */
    public SimCluster(Path dir, long blockSize, SimPki nodePki, String[] nodeSans, String nodeTls) throws Exception {
        this.pki = new SimPki(dir.resolve("pki"), "DFSha Root CA");
        SimPki issuer = nodePki == null ? pki : nodePki;
        for (int i = 1; i <= 3; i++) {
            String id = "dn-" + i;
            var identity = issuer.emitir(id, nodeSans);
            nodes.add(new SimNode(id, "az-" + i, identity, issuer.obtenerCaCert(), dir.resolve(id), nodeTls));
        }
        var identity = pki.emitir("server", "localhost", "127.0.0.1");
        this.server = new SimServer(pki.crearContextoServidor(identity), new String[] {"TLSv1.3"}, nodes, blockSize);
    }

    public SimServer obtenerServidor() {
        return server;
    }

    public SimNode obtenerNodo(int number) {
        return nodes.get(number - 1);
    }

    public List<SimNode> obtenerNodos() {
        return nodes;
    }

    public SimPki obtenerPki() {
        return pki;
    }

    @Override
    public void close() throws Exception {
        server.close();
        for (SimNode node : nodes) {
            node.close();
        }
    }
}
