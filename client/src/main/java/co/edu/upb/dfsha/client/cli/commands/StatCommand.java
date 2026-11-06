package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.cli.OutputFormatter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
/** Muestra los atributos de una ruta. */
@Command(name = "stat", mixinStandardHelpOptions = true, description = "Muestra los atributos de una ruta.")
public final class StatCommand extends ClientCommand {

    @Parameters(index = "0", paramLabel = "RUTA", description = "Ruta")
    private String path;

    public StatCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        salida().println(OutputFormatter.formatearAtributos(cliente().obtenerAtributos(path)));
    }
}
