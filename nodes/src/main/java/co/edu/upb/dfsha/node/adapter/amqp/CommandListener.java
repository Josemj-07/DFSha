package co.edu.upb.dfsha.node.adapter.amqp;

import co.edu.upb.dfsha.node.application.CommandDispatcher;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.domain.NodeCommand;
import co.edu.upb.dfsha.node.domain.ReplicationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Lee la cola de comandos de este nodo. Con 3 consumidores, una re-replicación lenta no bloquea
 * un DELETE o un RESYNC que llegue después.
 */
public class CommandListener {

    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    private final CommandParser parser;
    private final CommandDispatcher dispatcher;

    public CommandListener(CommandParser parser, CommandDispatcher dispatcher) {
        this.parser = parser;
        this.dispatcher = dispatcher;
    }

    @RabbitListener(queues = "dn.${dfs.node-id}.commands", concurrency = "3", messageConverter = "conversorComandos")
    public void recibir(Message mensaje) {
        NodeCommand comando;
        try {
            comando = parser.interpretar(mensaje.getBody());
        } catch (IllegalArgumentException e) {
            log.warn("Comando malformado descartado: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException("comando malformado", e);
        }
        try {
            if (!dispatcher.despachar(comando)) {
                log.debug("Comando {} repetido: se ignora", comando.commandId());
            }
        } catch (ReplicationFailedException | BlockNotFoundException e) {
            // Reintentar de inmediato no ayuda si el destino está caído o el bloque no está aquí;
            // el servidor vuelve a evaluar la reparación por su cuenta pasados 2 minutos.
            log.warn("Comando {} no completado: {}", comando.commandId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("comando no completado", e);
        }
    }
}
