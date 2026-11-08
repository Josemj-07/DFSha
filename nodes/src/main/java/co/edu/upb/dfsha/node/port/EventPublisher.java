package co.edu.upb.dfsha.node.port;

/** Publica eventos hacia el servidor por RabbitMQ. */
public interface EventPublisher {

    /** Para eventos que no se pueden perder: pasan por el outbox hasta que el broker confirma. */
    void publicarDurable(String routingKey, Object event);

    /** Para heartbeats: si el broker no está, se descartan. */
    void publicarVolatil(String routingKey, Object event);
}
