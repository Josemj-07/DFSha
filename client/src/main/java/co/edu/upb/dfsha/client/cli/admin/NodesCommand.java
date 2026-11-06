package co.edu.upb.dfsha.client.cli.admin;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.cli.OutputFormatter;
import picocli.CommandLine.Command;

/** Lista los DataNodes y su estado. */
@Command(name = "nodos", mixinStandardHelpOptions = true, description = "Lista los DataNodes y su estado.")
public final class NodesCommand extends ClientCommand {

    public NodesCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().listarNodos().forEach(node -> salida().println(OutputFormatter.formatearNodo(node)));
    }
}
