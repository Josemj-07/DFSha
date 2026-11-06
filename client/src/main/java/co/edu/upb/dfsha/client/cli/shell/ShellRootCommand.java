package co.edu.upb.dfsha.client.cli.shell;

import picocli.CommandLine.Command;

/** Raíz del árbol de comandos dentro de la sesión interactiva. */
@Command(name = "", description = "Comandos de la sesión. Usa exit para salir.",
        subcommands = {OpenCommand.class, ReadCommand.class, WriteCommand.class, FlushCommand.class,
                CloseCommand.class, LockCommand.class, UnlockCommand.class, picocli.CommandLine.HelpCommand.class})
final class ShellRootCommand {
}
