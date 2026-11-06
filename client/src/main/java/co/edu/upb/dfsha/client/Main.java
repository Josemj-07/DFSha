package co.edu.upb.dfsha.client;

import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.cli.CommandCatalog;

/** Punto de entrada de la CLI {@code dfs}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode;
        try (ClientContext context = ClientContext.desdeSistema()) {
            exitCode = CommandCatalog.crearRaiz(context).execute(args);
        }
        System.exit(exitCode);
    }
}
