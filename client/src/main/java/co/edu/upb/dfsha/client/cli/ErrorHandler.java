package co.edu.upb.dfsha.client.cli;

import co.edu.upb.dfsha.client.sdk.error.DfsException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import picocli.CommandLine;

/** Punto único donde un error se convierte en mensaje y código de salida. */
final class ErrorHandler implements CommandLine.IExecutionExceptionHandler {

    private final ClientContext context;

    ErrorHandler(ClientContext context) {
        this.context = context;
    }

    @Override
    public int handleExecutionException(Exception error, CommandLine command, CommandLine.ParseResult result) {
        PrintWriter err = context.obtenerErrores();
        err.println("dfs: " + describir(error));
        if (context.obtenerDetalle()) {
            error.printStackTrace(err);
        }
        return ExitCodes.obtenerCodigo(error);
    }

    private String describir(Exception error) {
        if (error instanceof DfsException) {
            return error.getMessage();
        }
        if (error instanceof UncheckedIOException) {
            return "Error de E/S local: " + error.getMessage();
        }
        return "Error inesperado: " + error.getMessage();
    }
}
