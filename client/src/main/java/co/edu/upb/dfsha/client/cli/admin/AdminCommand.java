package co.edu.upb.dfsha.client.cli.admin;

import picocli.CommandLine.Command;

/** Agrupa las operaciones de administración. */
@Command(name = "admin", mixinStandardHelpOptions = true, description = "Administración: requiere rol ADMIN.",
        subcommands = {CreateUserCommand.class, QuotaCommand.class, NodesCommand.class, AuditCommand.class})
public final class AdminCommand {
}
