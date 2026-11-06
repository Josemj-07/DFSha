package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import java.io.IOException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Muestra el final de un archivo. */
@Command(name = "tail", mixinStandardHelpOptions = true, description = "Muestra el final de un archivo.")
public final class TailCommand extends ClientCommand {

    @Option(names = "-n", paramLabel = "LINEAS", description = "Número de líneas: las últimas 10 por defecto")
    private long lines = 10;

    @Option(names = "-c", paramLabel = "BYTES", description = "Número de bytes, en lugar de líneas")
    private Long bytes;

    @Parameters(index = "0", paramLabel = "RUTA", description = "Archivo")
    private String path;

    public TailCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() throws IOException {
        cliente().copiarFinal(path, lines, bytes, context.obtenerSalidaBinaria());
        context.obtenerSalidaBinaria().flush();
    }
}
