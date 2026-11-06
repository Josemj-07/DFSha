package co.edu.upb.dfsha.client.cli;

import co.edu.upb.dfsha.client.cli.admin.AdminCommand;
import co.edu.upb.dfsha.client.cli.commands.CatCommand;
import co.edu.upb.dfsha.client.cli.commands.CdCommand;
import co.edu.upb.dfsha.client.cli.commands.CpCommand;
import co.edu.upb.dfsha.client.cli.commands.GetCommand;
import co.edu.upb.dfsha.client.cli.commands.HeadCommand;
import co.edu.upb.dfsha.client.cli.commands.LoginCommand;
import co.edu.upb.dfsha.client.cli.commands.LogoutCommand;
import co.edu.upb.dfsha.client.cli.commands.LsCommand;
import co.edu.upb.dfsha.client.cli.commands.MkdirCommand;
import co.edu.upb.dfsha.client.cli.commands.MvCommand;
import co.edu.upb.dfsha.client.cli.commands.PutCommand;
import co.edu.upb.dfsha.client.cli.commands.PwdCommand;
import co.edu.upb.dfsha.client.cli.commands.RmCommand;
import co.edu.upb.dfsha.client.cli.commands.RmdirCommand;
import co.edu.upb.dfsha.client.cli.commands.StatCommand;
import co.edu.upb.dfsha.client.cli.commands.TailCommand;
import co.edu.upb.dfsha.client.cli.shell.ShellCommand;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

/** Arma los árboles de comandos de la CLI y de la sesión interactiva. */
public final class CommandCatalog {

    private static final List<Class<?>> SHARED = List.of(LoginCommand.class, LogoutCommand.class, LsCommand.class,
            StatCommand.class, CdCommand.class, PwdCommand.class, MkdirCommand.class, RmdirCommand.class,
            RmCommand.class, MvCommand.class, CpCommand.class, PutCommand.class, GetCommand.class, CatCommand.class,
            HeadCommand.class, TailCommand.class, AdminCommand.class);
    private static final List<String> SHELL_ONLY =
            List.of("open", "read", "write", "flush", "close", "lock", "unlock");

    private CommandCatalog() {
    }

    public static CommandLine crearRaiz(ClientContext context) {
        CommandFactory factory = new CommandFactory(Map.of(ClientContext.class, context));
        CommandLine root = new CommandLine(new DfsCommand(context), factory);
        registrarCompartidos(root, factory);
        root.addSubcommand(new CommandLine(ShellCommand.class, factory));
        SHELL_ONLY.forEach(name -> root.addSubcommand(name, new ShellOnlyNotice(context, name)));
        return configurar(root, context);
    }

    /** Aplica a un árbol el manejo de errores común y los textos en español. */
    public static CommandLine configurar(CommandLine root, ClientContext context) {
        root.setExecutionExceptionHandler(new ErrorHandler(context));
        root.setOut(context.obtenerSalida());
        root.setErr(context.obtenerErrores());
        traducir(root);
        return root;
    }

    public static void registrarCompartidos(CommandLine root, CommandLine.IFactory factory) {
        SHARED.forEach(type -> root.addSubcommand(new CommandLine(type, factory)));
    }

    private static void traducir(CommandLine command) {
        var usage = command.getCommandSpec().usageMessage();
        usage.synopsisHeading("Uso: ").commandListHeading("%nComandos:%n").optionListHeading("Opciones:%n");
        command.getSubcommands().values().forEach(CommandCatalog::traducir);
    }
}
