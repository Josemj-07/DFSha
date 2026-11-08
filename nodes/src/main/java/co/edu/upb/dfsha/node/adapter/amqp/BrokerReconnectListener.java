package co.edu.upb.dfsha.node.adapter.amqp;

import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;

import java.util.concurrent.Executor;

/**
 * Cada vez que se abre la conexión con RabbitMQ (al arrancar o al reconectar), vuelve a enviar
 * el registro y el block report: mientras el broker estuvo caído el servidor pudo perder eventos.
 */
public class BrokerReconnectListener implements ConnectionListener {

    private final Runnable alConectar;
    private final Executor executor;

    /** @param executor se publica en otro hilo para no bloquear el que está abriendo la conexión */
    public BrokerReconnectListener(Runnable alConectar, Executor executor) {
        this.alConectar = alConectar;
        this.executor = executor;
    }

    @Override
    public void onCreate(Connection connection) {
        executor.execute(alConectar);
    }
}
