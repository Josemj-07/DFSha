package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
/** Copia un archivo sin duplicar sus bloques. */
@Command(name = "cp", mixinStandardHelpOptions = true, description = "Copia un archivo sin duplicar sus bloques.")
public final class CpCommand extends ClientCommand {

    @Parameters(index = "0", paramLabel = "ORIGEN", description = "Archivo")
    private String from;

    @Parameters(index = "1", paramLabel = "DESTINO", description = "Copia")
    private String to;

    public CpCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().copiar(from, to);
    }
}
