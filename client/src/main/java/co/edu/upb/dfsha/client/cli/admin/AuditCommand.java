package co.edu.upb.dfsha.client.cli.admin;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import picocli.CommandLine.Command;

/** Muestra el registro de auditoría. */
@Command(name = "auditoria", mixinStandardHelpOptions = true, description = "Muestra el registro de auditoría.")
public final class AuditCommand extends ClientCommand {

    public AuditCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        cliente().listarAuditoria().forEach(entry -> salida().printf("%s %-10s %-20s %-15s %s %s%n",
                entry.time(), entry.user(), entry.action(), entry.ip(), entry.result(), entry.traceId()));
    }
}
