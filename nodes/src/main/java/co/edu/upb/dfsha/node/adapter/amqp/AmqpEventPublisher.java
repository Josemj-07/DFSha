package co.edu.upb.dfsha.node.adapter.amqp;

import co.edu.upb.dfsha.node.port.EventPublisher;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Implementación del puerto EventPublisher. Los heartbeats salen directo; los demás eventos pasan
 * por el outbox, que los guarda en disco hasta que RabbitMQ los confirma.
 */
public class AmqpEventPublisher implements EventPublisher {

    private final AmqpSender sender;
    private final OutboxStore outbox;
    private final Runnable avisarDrenado;
    private final MessageConverter conversor;

    /** @param conversor el mismo de AmqpConfig, para que el JSON sea igual al que espera el servidor */
    public AmqpEventPublisher(AmqpSender sender, OutboxStore outbox, Runnable avisarDrenado,
                              MessageConverter conversor) {
        this.sender = sender;
        this.outbox = outbox;
        this.avisarDrenado = avisarDrenado;
        this.conversor = conversor;
    }

    @Override
    public void publicarVolatil(String routingKey, Object event) {
        sender.enviarVolatil(routingKey, serializar(event));
    }

    @Override
    public void publicarDurable(String routingKey, Object event) {
        String payload = new String(serializar(event), StandardCharsets.UTF_8);
        outbox.agregar(new OutboxEntry(UUID.randomUUID().toString(), routingKey, payload));
        avisarDrenado.run();
    }

    private byte[] serializar(Object event) {
        return conversor.toMessage(event, new MessageProperties()).getBody();
    }
}
