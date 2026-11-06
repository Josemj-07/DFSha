package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;

/** Cierra la sesión. */
@Command(name = "logout", mixinStandardHelpOptions = true, description = "Cierra la sesión.")
public final class LogoutCommand extends ClientCommand {

    public LogoutCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().cerrarSesion();
        salida().println("Sesión cerrada");
    }
}
