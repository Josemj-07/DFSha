package co.edu.upb.dfsha.node.adapter.amqp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Envía los eventos del outbox en orden, desde un único hilo. Si uno no se confirma, se detiene
 * y lo reintenta en la siguiente pasada: así el servidor nunca recibe un evento antes que otro
 * que ocurrió primero.
 */
public class OutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);

    private final OutboxStore outbox;
    private final AmqpSender sender;
    private final ScheduledExecutorService hilo;
    private final AtomicBoolean pendiente = new AtomicBoolean();

    /** @param hilo debe tener un solo hilo: es lo que garantiza el orden */
    public OutboxDrainer(OutboxStore outbox, AmqpSender sender, ScheduledExecutorService hilo) {
        this.outbox = outbox;
        this.sender = sender;
        this.hilo = hilo;
    }

    /** Reintenta periódicamente lo que no se pudo enviar, por ejemplo mientras el broker está caído. */
    public void iniciar(Duration periodo) {
        hilo.scheduleWithFixedDelay(this::drenar, 0, periodo.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Pide una pasada pronto. Varias llamadas seguidas producen una sola pasada. */
    public void avisar() {
        if (pendiente.compareAndSet(false, true)) {
            hilo.execute(() -> {
                pendiente.set(false);
                drenar();
            });
        }
    }

    /** @return cuántos eventos se confirmaron en esta pasada */
    int drenar() {
        int enviados = 0;
        try {
            for (OutboxEntry entrada : outbox.listar()) {
                if (!sender.enviarConfirmado(entrada)) {
                    log.debug("RabbitMQ no confirmó {}; se reintenta luego", entrada.routingKey());
                    return enviados;
                }
                outbox.confirmar(entrada.id());
                enviados++;
            }
        } catch (RuntimeException e) {
            // Una excepción en una tarea programada la cancelaría para siempre; se registra y se sigue.
            log.warn("Error al drenar el outbox", e);
        }
        return enviados;
    }
}
