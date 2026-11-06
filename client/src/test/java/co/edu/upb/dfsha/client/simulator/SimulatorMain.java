package co.edu.upb.dfsha.client.simulator;

import java.nio.file.Path;

/** Levanta el simulador para probar la CLI a mano, mientras no existan el servidor y los nodos reales. */
public final class SimulatorMain {

    private static final long DEFAULT_BLOCK_SIZE = 128L * 1024 * 1024;

    private SimulatorMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/simulador");
        long blockSize = Long.getLong("dfs.simulador.bloque", DEFAULT_BLOCK_SIZE);
        SimCluster cluster = new SimCluster(dir, blockSize);
        cluster.obtenerServidor().crearUsuario("admin", "admin-simulador");
        System.out.println("Simulador listo. Usuario admin, contraseña admin-simulador");
        System.out.println("export DFS_SERVER_URL=" + cluster.obtenerServidor().obtenerUrl());
        System.out.println("export DFS_CA_CERT=" + cluster.obtenerPki().obtenerCaCert().toAbsolutePath());
        Thread.currentThread().join();
    }
}
