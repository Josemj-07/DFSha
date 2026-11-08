package co.edu.upb.dfsha.node.adapter.amqp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica en dfs.events. Todo mensaje lleva user_id igual al ID del nodo: RabbitMQ rechaza el
 * mensaje si no coincide con el usuario de la conexión, y el servidor descarta los que no
 * coinciden con la routing key. Así ningún nodo puede hacerse pasar por otro.
 */
public class AmqpMessageSender implements AmqpSender {

    static final String EXCHANGE = "dfs.events";
    private static final long ESPERA_CONFIRMACION_SEGUNDOS = 5;
    private static final Logger log = LoggerFactory.getLogger(AmqpMessageSender.class);

    private final RabbitTemplate template;
    private final String nodeId;

    public AmqpMessageSender(RabbitTemplate template, String nodeId) {
        this.template = template;
        this.nodeId = nodeId;
    }

    @Override
    public void enviarVolatil(String routingKey, byte[] cuerpo) {
        try {
            template.send(EXCHANGE, routingKey, crearMensaje(cuerpo, MessageDeliveryMode.NON_PERSISTENT, null));
        } catch (AmqpException e) {
            log.debug("Mensaje {} descartado: RabbitMQ no disponible", routingKey);
        }
    }

    @Override
    public boolean enviarConfirmado(OutboxEntry entrada) {
        byte[] cuerpo = entrada.payload().getBytes(StandardCharsets.UTF_8);
        var correlation = new CorrelationData(entrada.id());
        try {
            template.send(EXCHANGE, entrada.routingKey(),
                    crearMensaje(cuerpo, MessageDeliveryMode.PERSISTENT, entrada.id()), correlation);
            return correlation.getFuture().get(ESPERA_CONFIRMACION_SEGUNDOS, TimeUnit.SECONDS).ack();
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Message crearMensaje(byte[] cuerpo, MessageDeliveryMode modo, String messageId) {
        var propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propiedades.setUserId(nodeId);
        propiedades.setDeliveryMode(modo);
        propiedades.setMessageId(messageId);
        return new Message(cuerpo, propiedades);
    }
}
