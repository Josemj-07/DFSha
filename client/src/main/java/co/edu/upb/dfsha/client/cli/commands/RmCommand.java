package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
/** Borra un archivo. */
@Command(name = "rm", mixinStandardHelpOptions = true, description = "Borra un archivo.")
public final class RmCommand extends ClientCommand {

    @Parameters(index = "0", paramLabel = "RUTA", description = "Archivo")
    private String path;

    public RmCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().borrar(path);
    }
}
