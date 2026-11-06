package co.edu.upb.dfsha.client.cli;

import co.edu.upb.dfsha.client.sdk.DfsClient;
import java.io.PrintWriter;
import java.util.concurrent.Callable;

/** Base de los comandos: acceso al cliente y a la salida. */
public abstract class ClientCommand implements Callable<Integer> {

    protected final ClientContext context;

    protected ClientCommand(ClientContext context) {
        this.context = context;
    }

    protected abstract void ejecutar() throws Exception;

    @Override
    public final Integer call() throws Exception {
        ejecutar();
        return ExitCodes.OK;
    }

    protected DfsClient cliente() {
        return context.obtenerCliente();
    }

    protected PrintWriter salida() {
        return context.obtenerSalida();
    }
}
