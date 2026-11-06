package co.edu.upb.dfsha.client.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Comando raíz. Las opciones se guardan en el contexto, que comparten todos los subcomandos. */
@Command(name = "dfs", mixinStandardHelpOptions = true, version = "dfs 0.1",
        description = "Cliente de DFSha, sistema de archivos distribuido.")
public final class DfsCommand implements Callable<Integer> {

    private final ClientContext context;

    public DfsCommand(ClientContext context) {
        this.context = context;
    }

    @Option(names = "--server", paramLabel = "URL", description = "URL del servidor, por ejemplo https://10.0.1.10:8443")
    void establecerServidor(String value) {
        context.establecerServidor(value);
    }

    @Option(names = "--ca-cert", paramLabel = "ARCHIVO", description = "Certificado de la CA del proyecto")
    void establecerCertificadoCa(String value) {
        context.establecerCertificadoCa(value);
    }

    @Option(names = "--verbose", description = "Muestra el detalle de los errores")
    void establecerDetalle(boolean value) {
        context.establecerDetalle(value);
    }

    @Override
    public Integer call() {
        context.obtenerErrores().println("dfs: falta un comando. Usa dfs --help");
        return ExitCodes.USAGE;
    }
}
