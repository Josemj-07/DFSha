package co.edu.upb.dfsha.node.adapter.amqp;

import co.edu.upb.dfsha.node.application.CommandDispatcher;
import co.edu.upb.dfsha.node.domain.ReplicateBlockCommand;
import co.edu.upb.dfsha.node.domain.ReplicationFailedException;
import co.edu.upb.dfsha.node.domain.ResyncCommand;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandListenerTest {

    private final List<String> ejecutados = new ArrayList<>();
    private final CommandDispatcher dispatcher = new CommandDispatcher()
            .registrar(ResyncCommand.class, comando -> ejecutados.add(comando.commandId()))
            .registrar(ReplicateBlockCommand.class, comando -> {
                throw new ReplicationFailedException("destino caído");
            });
    private final CommandListener listener = new CommandListener(new CommandParser(JsonMapper.builder().build()),
            dispatcher);

    @Test
    void ejecutaUnComandoValidoUnaSolaVez() {
        listener.recibir(mensaje("{\"commandId\":\"c-1\",\"type\":\"RESYNC\"}"));
        listener.recibir(mensaje("{\"commandId\":\"c-1\",\"type\":\"RESYNC\"}"));

        assertEquals(List.of("c-1"), ejecutados);
    }

    @Test
    void unComandoMalFormadoSeDescartaSinReencolar() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.recibir(mensaje("{basura")));
    }

    @Test
    void unaReplicacionFallidaNoSeReencolaPorqueElServidorReintenta() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.recibir(mensaje("""
                {"commandId":"c-2","type":"REPLICATE_BLOCK","blockId":"blk_01","gen":1,
                 "target":{"nodeId":"dn-3","addr":"x:50052"},"token":"t"}""")));
    }

    private static Message mensaje(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
