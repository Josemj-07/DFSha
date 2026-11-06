package co.edu.upb.dfsha.client.cli;

import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;

/** Explica que un comando de RF3 solo funciona dentro de la sesión interactiva. */
@Command(hidden = true)
final class ShellOnlyNotice implements Callable<Integer> {

    private final ClientContext context;
    private final String name;

    @Unmatched
    private List<String> ignored;

    ShellOnlyNotice(ClientContext context, String name) {
        this.context = context;
        this.name = name;
    }

    @Override
    public Integer call() {
        context.obtenerErrores().println("dfs: " + name + " necesita un proceso vivo. Úsalo dentro de dfs shell");
        return ExitCodes.USAGE;
    }
}
