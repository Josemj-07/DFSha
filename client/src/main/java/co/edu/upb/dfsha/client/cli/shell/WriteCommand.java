package co.edu.upb.dfsha.client.cli.shell;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Escribe texto en un descriptor. Queda local hasta flush o close. */
@Command(name = "write", mixinStandardHelpOptions = true, description = "Escribe texto en un descriptor. Queda local hasta flush o close.")
public final class WriteCommand extends ClientCommand {

    private final DescriptorTable table;

    @Parameters(index = "0", paramLabel = "FD", description = "Descriptor")
    private int descriptor;

    @Parameters(index = "1", paramLabel = "OFFSET", description = "Posición")
    private long offset;

    @Parameters(index = "2", paramLabel = "DATOS", description = "Texto, entre comillas si tiene espacios")
    private String data;

    public WriteCommand(ClientContext context, DescriptorTable table) {
        super(context);
        this.table = table;
    }

    @Override
    protected void ejecutar() {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        table.obtener(descriptor).escribir(offset, ByteBuffer.wrap(bytes));
        salida().println(bytes.length + " bytes en el buffer local");
    }
}
