package co.edu.upb.dfsha.client.cli;

import co.edu.upb.dfsha.client.config.ClientConfig;
import co.edu.upb.dfsha.client.sdk.DfsClient;
import co.edu.upb.dfsha.client.sdk.DfsClientFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Estado de una ejecución: opciones globales, flujos y cliente. El cliente se crea al primer uso,
 * para que {@code --help} funcione sin configuración.
 */
public final class ClientContext implements AutoCloseable {

    private final Map<String, String> env;
    private final String userHome;
    private final InputStream in;
    private final OutputStream binaryOut;
    private final PrintWriter out;
    private final PrintWriter err;
    private String serverOption;
    private String caOption;
    private boolean verbose;
    private DfsClient client;

    public ClientContext(Map<String, String> env, String userHome, InputStream in, OutputStream out, OutputStream err) {
        this.env = env;
        this.userHome = userHome;
        this.in = in;
        this.binaryOut = out;
        this.out = new PrintWriter(out, true, StandardCharsets.UTF_8);
        this.err = new PrintWriter(err, true, StandardCharsets.UTF_8);
    }

    public static ClientContext desdeSistema() {
        return new ClientContext(System.getenv(), System.getProperty("user.home"), System.in, System.out, System.err);
    }

    public DfsClient obtenerCliente() {
        if (client == null) {
            ClientConfig config = ClientConfig.cargar(serverOption, caOption, env, userHome);
            client = DfsClientFactory.crear(config, err::println);
        }
        return client;
    }

    public void establecerServidor(String value) {
        serverOption = value;
    }

    public void establecerCertificadoCa(String value) {
        caOption = value;
    }

    public void establecerDetalle(boolean value) {
        verbose = value;
    }

    public boolean obtenerDetalle() {
        return verbose;
    }

    public PrintWriter obtenerSalida() {
        return out;
    }

    public PrintWriter obtenerErrores() {
        return err;
    }

    /** Salida sin codificar, para los bytes de un archivo. */
    public OutputStream obtenerSalidaBinaria() {
        return binaryOut;
    }

    public InputStream obtenerEntrada() {
        return in;
    }

    @Override
    public void close() {
        out.flush();
        err.flush();
        if (client != null) {
            client.close();
        }
    }
}
