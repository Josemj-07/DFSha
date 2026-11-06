package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
/** Escribe un archivo en la salida estándar. */
@Command(name = "cat", mixinStandardHelpOptions = true, description = "Escribe un archivo en la salida estándar.")
public final class CatCommand extends ClientCommand {

    @Parameters(index = "0", paramLabel = "RUTA", description = "Archivo")
    private String path;

    public CatCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() throws java.io.IOException {
        cliente().copiarContenido(path, context.obtenerSalidaBinaria());
        context.obtenerSalidaBinaria().flush();
    }
}
