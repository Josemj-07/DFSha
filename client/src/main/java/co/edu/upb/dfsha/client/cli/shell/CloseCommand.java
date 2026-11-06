package co.edu.upb.dfsha.client.cli.shell;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.sdk.DfsFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Publica los cambios y cierra un descriptor. */
@Command(name = "close", mixinStandardHelpOptions = true, description = "Publica los cambios y cierra un descriptor.")
public final class CloseCommand extends ClientCommand {

    private final DescriptorTable table;

    @Parameters(index = "0", paramLabel = "FD", description = "Descriptor")
    private int descriptor;

    public CloseCommand(ClientContext context, DescriptorTable table) {
        super(context);
        this.table = table;
    }

    @Override
    protected void ejecutar() {
        DfsFile file = table.obtener(descriptor);
        table.quitar(descriptor);
        file.close();
    }
}
