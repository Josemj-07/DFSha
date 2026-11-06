package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
/** Renombra o mueve una ruta. */
@Command(name = "mv", mixinStandardHelpOptions = true, description = "Renombra o mueve una ruta.")
public final class MvCommand extends ClientCommand {

    @Parameters(index = "0", paramLabel = "ORIGEN", description = "Ruta actual")
    private String from;

    @Parameters(index = "1", paramLabel = "DESTINO", description = "Ruta nueva")
    private String to;

    public MvCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().mover(from, to);
    }
}
