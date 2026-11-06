package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.cli.OutputFormatter;
import co.edu.upb.dfsha.client.sdk.model.FileInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
/** Lista un directorio. */
@Command(name = "ls", mixinStandardHelpOptions = true, description = "Lista un directorio.")
public final class LsCommand extends ClientCommand {

    @Option(names = "-l", description = "Formato largo")
    private boolean detailed;

    @Parameters(index = "0", arity = "0..1", paramLabel = "RUTA", description = "Directorio")
    private String path = ".";

    public LsCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        for (FileInfo entry : cliente().listar(path)) {
            salida().println(detailed ? OutputFormatter.formatearDetalle(entry) : entry.name());
        }
    }
}
