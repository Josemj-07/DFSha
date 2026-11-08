package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.NodeCommand;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Envía cada comando a su handler. RabbitMQ puede entregar un comando dos veces; los que ya se
 * ejecutaron bien se ignoran por commandId. Aun así cada handler es idempotente por sí mismo,
 * porque esta memoria se pierde al reiniciar.
 */
public class CommandDispatcher {

    private static final int RECORDADOS = 1000;

    private final Map<Class<? extends NodeCommand>, CommandHandler<? extends NodeCommand>> handlers = new HashMap<>();
    private final Map<String, Boolean> ejecutados = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> masAntiguo) {
            return size() > RECORDADOS;
        }
    };

    public <T extends NodeCommand> CommandDispatcher registrar(Class<T> tipo, CommandHandler<T> handler) {
        handlers.put(tipo, handler);
        return this;
    }

    /** @return false si el comando ya se había ejecutado y se ignoró */
    public boolean despachar(NodeCommand comando) {
        if (yaEjecutado(comando.commandId())) {
            return false;
        }
        buscarHandler(comando).ejecutar(comando);
        recordar(comando.commandId());
        return true;
    }

    @SuppressWarnings("unchecked")
    private CommandHandler<NodeCommand> buscarHandler(NodeCommand comando) {
        var handler = (CommandHandler<NodeCommand>) handlers.get(comando.getClass());
        if (handler == null) {
            throw new IllegalArgumentException("comando sin handler: " + comando.getClass().getSimpleName());
        }
        return handler;
    }

    private synchronized boolean yaEjecutado(String commandId) {
        return ejecutados.containsKey(commandId);
    }

    private synchronized void recordar(String commandId) {
        ejecutados.put(commandId, Boolean.TRUE);
    }
}
