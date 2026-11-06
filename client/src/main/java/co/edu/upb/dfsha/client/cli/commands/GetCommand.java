package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.cli.TerminalProgress;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
/** Descarga un archivo remoto. */
@Command(name = "get", mixinStandardHelpOptions = true, description = "Descarga un archivo remoto.")
public final class GetCommand extends ClientCommand {

    @Parameters(index = "0", paramLabel = "REMOTO", description = "Ruta remota")
    private String remote;

    @Parameters(index = "1", paramLabel = "LOCAL", description = "Destino local")
    private Path local;

    public GetCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().descargar(remote, local, TerminalProgress.crear(context, "Descargando"));
    }
}
