package co.edu.upb.dfsha.node.adapter.amqp;

/**
 * Un evento pendiente de confirmar por RabbitMQ.
 *
 * @param payload cuerpo JSON ya serializado: se envía tal cual, aunque el nodo se haya reiniciado
 */
public record OutboxEntry(String id, String routingKey, String payload) {
}
