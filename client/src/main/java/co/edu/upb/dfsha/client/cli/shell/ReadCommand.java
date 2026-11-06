package co.edu.upb.dfsha.client.cli.shell;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Lee bytes de un descriptor. */
@Command(name = "read", mixinStandardHelpOptions = true, description = "Lee bytes de un descriptor.")
public final class ReadCommand extends ClientCommand {

    private final DescriptorTable table;

    @Parameters(index = "0", paramLabel = "FD", description = "Descriptor")
    private int descriptor;

    @Parameters(index = "1", paramLabel = "OFFSET", description = "Posición")
    private long offset;

    @Parameters(index = "2", paramLabel = "LONGITUD", description = "Bytes a leer")
    private int length;

    public ReadCommand(ClientContext context, DescriptorTable table) {
        super(context);
        this.table = table;
    }

    @Override
    protected void ejecutar() throws java.io.IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        int read = table.obtener(descriptor).leer(offset, buffer);
        if (read > 0) {
            context.obtenerSalidaBinaria().write(buffer.array(), 0, read);
            context.obtenerSalidaBinaria().write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
            context.obtenerSalidaBinaria().flush();
        }
    }
}
