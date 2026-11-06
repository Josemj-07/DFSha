package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
/** Borra un directorio. */
@Command(name = "rmdir", mixinStandardHelpOptions = true, description = "Borra un directorio.")
public final class RmdirCommand extends ClientCommand {

    @Option(names = "-r", description = "Borra también su contenido")
    private boolean recursive;

    @Parameters(index = "0", paramLabel = "RUTA", description = "Directorio")
    private String path;

    public RmdirCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().borrarDirectorio(path, recursive);
    }
}
