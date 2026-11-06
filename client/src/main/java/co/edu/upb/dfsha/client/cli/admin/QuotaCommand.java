package co.edu.upb.dfsha.client.cli.admin;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Asigna la cuota de un usuario. */
@Command(name = "cuota", mixinStandardHelpOptions = true, description = "Asigna la cuota de un usuario.")
public final class QuotaCommand extends ClientCommand {

    @Parameters(index = "0", paramLabel = "USUARIO", description = "Usuario")
    private String username;

    @Parameters(index = "1", paramLabel = "BYTES", description = "Cuota en bytes")
    private long quotaBytes;

    public QuotaCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().asignarCuota(username, quotaBytes);
    }
}
