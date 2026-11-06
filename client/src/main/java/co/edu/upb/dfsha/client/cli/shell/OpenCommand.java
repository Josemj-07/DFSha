package co.edu.upb.dfsha.client.cli.shell;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.sdk.model.OpenMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Abre un archivo y devuelve su descriptor. */
@Command(name = "open", mixinStandardHelpOptions = true, description = "Abre un archivo y devuelve su descriptor.")
public final class OpenCommand extends ClientCommand {

    private final DescriptorTable table;

    @Option(names = "--exclusive", description = "Falla si el archivo ya existe")
    private boolean exclusive;

    @Parameters(index = "0", paramLabel = "RUTA", description = "Archivo")
    private String path;

    @Parameters(index = "1", paramLabel = "MODO", description = "r, w, a o rw")
    private String mode;

    public OpenCommand(ClientContext context, DescriptorTable table) {
        super(context);
        this.table = table;
    }

    @Override
    protected void ejecutar() {
        salida().println(table.registrar(cliente().abrir(path, OpenMode.desdeValor(mode), exclusive)));
    }
}
