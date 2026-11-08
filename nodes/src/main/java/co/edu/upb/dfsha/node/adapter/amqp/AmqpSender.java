package co.edu.upb.dfsha.node.adapter.amqp;

/** Envío de bajo nivel al exchange dfs.events. */
public interface AmqpSender {

    /** Sin persistencia ni confirmación; si el broker no está, se descarta. */
    void enviarVolatil(String routingKey, byte[] cuerpo);

    /** Persistente; devuelve true solo si RabbitMQ confirmó que lo recibió. */
    boolean enviarConfirmado(OutboxEntry entrada);
}
