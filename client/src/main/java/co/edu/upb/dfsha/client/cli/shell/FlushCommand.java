package co.edu.upb.dfsha.client.cli.shell;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Publica los cambios de un descriptor. */
@Command(name = "flush", mixinStandardHelpOptions = true, description = "Publica los cambios de un descriptor.")
public final class FlushCommand extends ClientCommand {

    private final DescriptorTable table;

    @Parameters(index = "0", paramLabel = "FD", description = "Descriptor")
    private int descriptor;

    public FlushCommand(ClientContext context, DescriptorTable table) {
        super(context);
        this.table = table;
    }

    @Override
    protected void ejecutar() {
        salida().println("Versión " + table.obtener(descriptor).volcar());
    }
}
