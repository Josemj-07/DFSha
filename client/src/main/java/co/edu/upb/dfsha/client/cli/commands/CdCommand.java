package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
/** Cambia el directorio actual. */
@Command(name = "cd", mixinStandardHelpOptions = true, description = "Cambia el directorio actual.")
public final class CdCommand extends ClientCommand {

    @Parameters(index = "0", paramLabel = "RUTA", description = "Directorio")
    private String path;

    public CdCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().cambiarDirectorio(path);
    }
}
