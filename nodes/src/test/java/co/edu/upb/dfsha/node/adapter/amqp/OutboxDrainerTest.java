package co.edu.upb.dfsha.node.adapter.amqp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxDrainerTest {

    @TempDir
    Path dir;

    private OutboxStore outbox;
    private BrokerSimulado broker;
    private ScheduledExecutorService hilo;
    private OutboxDrainer drainer;

    @BeforeEach
    void preparar() {
        outbox = new OutboxStore(dir, JsonMapper.builder().build());
        broker = new BrokerSimulado();
        hilo = Executors.newSingleThreadScheduledExecutor();
        drainer = new OutboxDrainer(outbox, broker, hilo);
        for (String id : List.of("e-1", "e-2", "e-3")) {
            outbox.agregar(new OutboxEntry(id, "block.received.dn-1", "{}"));
        }
    }

    @AfterEach
    void detener() {
        hilo.shutdownNow();
    }

    @Test
    void conElBrokerDisponibleEnviaTodoEnOrdenYVaciaElOutbox() {
        assertEquals(3, drainer.drenar());

        assertEquals(List.of("e-1", "e-2", "e-3"), broker.recibidos);
        assertTrue(outbox.listar().isEmpty());
    }

    @Test
    void conElBrokerCaidoNoBorraNada() {
        broker.disponible = false;

        assertEquals(0, drainer.drenar());

        assertEquals(3, outbox.listar().size());
    }

    @Test
    void siUnEventoNoSeConfirmaSeDetieneParaNoAlterarElOrden() {
        broker.confirmarHasta = 1;

        drainer.drenar();

        assertEquals(List.of("e-1"), broker.recibidos);
        assertEquals(List.of("e-2", "e-3"), outbox.listar().stream().map(OutboxEntry::id).toList());
    }

    @Test
    void cuandoVuelveElBrokerEnviaLoAcumulado() {
        broker.disponible = false;
        drainer.drenar();
        broker.disponible = true;

        drainer.drenar();

        assertEquals(List.of("e-1", "e-2", "e-3"), broker.recibidos);
        assertTrue(outbox.listar().isEmpty());
    }

    @Test
    void avisarDrenaEnSegundoPlano() throws InterruptedException {
        drainer.avisar();

        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!outbox.listar().isEmpty() && System.nanoTime() < limite) {
            Thread.sleep(20);
        }
        assertTrue(outbox.listar().isEmpty());
    }

    /** Doble de AmqpSender que confirma o no según se configure. */
    private static final class BrokerSimulado implements AmqpSender {

        final List<String> recibidos = new CopyOnWriteArrayList<>();
        volatile boolean disponible = true;
        volatile int confirmarHasta = Integer.MAX_VALUE;

        @Override
        public void enviarVolatil(String routingKey, byte[] cuerpo) {
        }

        @Override
        public boolean enviarConfirmado(OutboxEntry entrada) {
            if (!disponible || recibidos.size() >= confirmarHasta) {
                return false;
            }
            recibidos.add(entrada.id());
            return true;
        }
    }
}
