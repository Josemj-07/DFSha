package co.edu.upb.dfsha.node.adapter.amqp;

import co.edu.upb.dfsha.node.config.AmqpConfig;
import co.edu.upb.dfsha.node.domain.BlockEvent;
import co.edu.upb.dfsha.node.domain.Heartbeat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmqpEventPublisherTest {

    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @TempDir
    Path dir;

    private final List<String> volatiles = new ArrayList<>();
    private int avisos;

    @Test
    void unEventoDurableQuedaEnElOutboxAntesDeEnviarse() {
        var outbox = new OutboxStore(dir, JsonMapper.builder().build());

        publicador(outbox).publicarDurable("block.received.dn-1", new BlockEvent("e-1", "dn-1", "blk_01", 1, 10, SHA));

        var entrada = outbox.listar().getFirst();
        assertEquals("block.received.dn-1", entrada.routingKey());
        assertTrue(entrada.payload().contains("\"blockId\":\"blk_01\""));
        assertEquals(1, avisos);
        assertTrue(volatiles.isEmpty());
    }

    @Test
    void unHeartbeatNoPasaPorElOutbox() {
        var outbox = new OutboxStore(dir, JsonMapper.builder().build());

        publicador(outbox).publicarVolatil("node.heartbeat.dn-1", new Heartbeat("dn-1", 10, 5, 0, 0, "t"));

        assertTrue(outbox.listar().isEmpty());
        assertEquals(1, volatiles.size());
        assertTrue(volatiles.getFirst().startsWith("node.heartbeat.dn-1 {"));
    }

    private AmqpEventPublisher publicador(OutboxStore outbox) {
        AmqpSender sender = new AmqpSender() {
            @Override
            public void enviarVolatil(String routingKey, byte[] cuerpo) {
                volatiles.add(routingKey + " " + new String(cuerpo, StandardCharsets.UTF_8));
            }

            @Override
            public boolean enviarConfirmado(OutboxEntry entrada) {
                return true;
            }
        };
        return new AmqpEventPublisher(sender, outbox, () -> avisos++, new AmqpConfig().conversorMensajes());
    }
}
