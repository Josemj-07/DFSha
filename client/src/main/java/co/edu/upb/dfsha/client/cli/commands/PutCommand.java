package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.cli.TerminalProgress;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
/** Sube un archivo local. */
@Command(name = "put", mixinStandardHelpOptions = true, description = "Sube un archivo local.")
public final class PutCommand extends ClientCommand {

    @Option(names = "--exclusive", description = "Falla si el archivo remoto ya existe")
    private boolean exclusive;

    @Parameters(index = "0", paramLabel = "LOCAL", description = "Archivo local")
    private Path local;

    @Parameters(index = "1", paramLabel = "REMOTO", description = "Ruta remota")
    private String remote;

    public PutCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        long version = cliente().subir(local, remote, exclusive, TerminalProgress.crear(context, "Subiendo"));
        salida().println("Subido, versión " + version);
    }
}
