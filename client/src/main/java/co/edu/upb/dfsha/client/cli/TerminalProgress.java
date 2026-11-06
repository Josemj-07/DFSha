package co.edu.upb.dfsha.client.cli;

import co.edu.upb.dfsha.client.sdk.model.ProgressListener;
import java.io.PrintWriter;

/** Muestra el avance en {@code stderr}, para no mezclarlo con los datos de {@code stdout}. */
public final class TerminalProgress implements ProgressListener {

    private final PrintWriter err;
    private final String label;

    private TerminalProgress(PrintWriter err, String label) {
        this.err = err;
        this.label = label;
    }

    public static ProgressListener crear(ClientContext context, String label) {
        return System.console() == null ? ProgressListener.NINGUNO
                : new TerminalProgress(context.obtenerErrores(), label);
    }

    @Override
    public synchronized void notificarBloque(int done, int total) {
        err.printf("\r%s: %d/%d bloques", label, done, total);
        if (done == total) {
            err.println();
        }
        err.flush();
    }
}
