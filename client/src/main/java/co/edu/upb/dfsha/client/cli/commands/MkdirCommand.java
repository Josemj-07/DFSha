package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
/** Crea un directorio. */
@Command(name = "mkdir", mixinStandardHelpOptions = true, description = "Crea un directorio.")
public final class MkdirCommand extends ClientCommand {

    @Option(names = "-p", description = "Crea también los directorios padres")
    private boolean parents;

    @Parameters(index = "0", paramLabel = "RUTA", description = "Directorio")
    private String path;

    public MkdirCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().crearDirectorio(path, parents);
    }
}
