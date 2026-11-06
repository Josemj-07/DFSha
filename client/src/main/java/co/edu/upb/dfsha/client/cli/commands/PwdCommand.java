package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;

/** Muestra el directorio actual. */
@Command(name = "pwd", mixinStandardHelpOptions = true, description = "Muestra el directorio actual.")
public final class PwdCommand extends ClientCommand {

    public PwdCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        salida().println(cliente().obtenerDirectorioActual());
    }
}
