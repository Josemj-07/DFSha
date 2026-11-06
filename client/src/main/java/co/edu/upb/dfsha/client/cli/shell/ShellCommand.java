package co.edu.upb.dfsha.client.cli.shell;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.cli.CommandCatalog;
import co.edu.upb.dfsha.client.cli.CommandFactory;
import co.edu.upb.dfsha.client.sdk.DfsFile;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Sesión interactiva: mantiene vivos los descriptores, los locks y sus renovaciones. */
@Command(name = "shell", mixinStandardHelpOptions = true, description = "Abre la sesión interactiva.")
public final class ShellCommand extends ClientCommand {

    private static final String EXIT = "exit";

    private final DescriptorTable table = new DescriptorTable();

    public ShellCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() throws IOException {
        cliente();
        CommandLine commands = crearComandos();
        Thread hook = new Thread(this::cerrarTodo, "dfs-shell-exit");
        Runtime.getRuntime().addShutdownHook(hook);
        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            leerComandos(LineReaderBuilder.builder().terminal(terminal).build(), commands);
        } finally {
            Runtime.getRuntime().removeShutdownHook(hook);
            cerrarTodo();
        }
    }

    private void leerComandos(LineReader reader, CommandLine commands) {
        while (true) {
            String line;
            try {
                line = reader.readLine(obtenerIndicador());
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                return;
            }
            List<String> words = reader.getParser().parse(line, 0).words().stream()
                    .filter(word -> !word.isEmpty()).toList();
            if (words.equals(List.of(EXIT))) {
                return;
            }
            if (!words.isEmpty()) {
                commands.execute(words.toArray(String[]::new));
            }
        }
    }

    private String obtenerIndicador() {
        try {
            return "dfs:" + cliente().obtenerDirectorioActual() + "> ";
        } catch (DfsException e) {
            return "dfs> ";
        }
    }

    private CommandLine crearComandos() {
        CommandFactory factory = new CommandFactory(Map.of(ClientContext.class, context, DescriptorTable.class, table));
        CommandLine commands = new CommandLine(new ShellRootCommand(), factory);
        CommandCatalog.registrarCompartidos(commands, factory);
        return CommandCatalog.configurar(commands, context);
    }

    /** Publica lo pendiente y libera los recursos. Corre también si el proceso recibe una señal. */
    private void cerrarTodo() {
        for (DfsFile file : table.listarArchivos()) {
            if (file.tieneCambiosPendientes()) {
                context.obtenerErrores().println("Aviso: publicando cambios sin flush de " + file.obtenerRuta());
            }
            cerrarSinFallar(file::close);
        }
        table.vaciar();
        for (String lockId : table.listarBloqueosLibres()) {
            table.quitarBloqueoLibre(lockId);
            cerrarSinFallar(() -> cliente().desbloquear(lockId));
        }
    }

    private void cerrarSinFallar(Runnable action) {
        try {
            action.run();
        } catch (DfsException e) {
            context.obtenerErrores().println("Aviso: " + e.getMessage());
        }
    }
}
