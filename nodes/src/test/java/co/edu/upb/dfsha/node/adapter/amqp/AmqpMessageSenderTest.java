package co.edu.upb.dfsha.node.adapter.amqp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AmqpMessageSenderTest {

    private static final OutboxEntry EVENTO = new OutboxEntry("e-1", "block.received.dn-1", "{\"x\":1}");

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final AmqpMessageSender sender = new AmqpMessageSender(template, "dn-1");

    @Test
    void unHeartbeatSaleConElUserIdDelNodoYSinPersistencia() {
        sender.enviarVolatil("node.heartbeat.dn-1", new byte[] {1});

        var mensaje = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("dfs.events"), eq("node.heartbeat.dn-1"), mensaje.capture());
        assertEquals("dn-1", mensaje.getValue().getMessageProperties().getUserId());
        assertEquals(MessageDeliveryMode.NON_PERSISTENT, mensaje.getValue().getMessageProperties().getDeliveryMode());
    }

    @Test
    void sinBrokerUnHeartbeatSeDescartaSinError() {
        doThrow(new AmqpConnectException(new ConnectException("sin broker"))).when(template)
                .send(anyString(), anyString(), any(Message.class));

        assertDoesNotThrow(() -> sender.enviarVolatil("node.heartbeat.dn-1", new byte[0]));
    }

    @Test
    void unEventoDelOutboxEsPersistenteYEsperaLaConfirmacion() {
        confirmarCon(true);

        assertTrue(sender.enviarConfirmado(EVENTO));

        var mensaje = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("dfs.events"), eq("block.received.dn-1"), mensaje.capture(), any(CorrelationData.class));
        var propiedades = mensaje.getValue().getMessageProperties();
        assertEquals("dn-1", propiedades.getUserId());
        assertEquals(MessageDeliveryMode.PERSISTENT, propiedades.getDeliveryMode());
        assertEquals("e-1", propiedades.getMessageId());
        assertArrayEquals("{\"x\":1}".getBytes(), mensaje.getValue().getBody());
    }

    @Test
    void unNackCuentaComoNoEnviado() {
        confirmarCon(false);

        assertFalse(sender.enviarConfirmado(EVENTO));
    }

    @Test
    void sinBrokerUnEventoNoSeDaPorEnviado() {
        doThrow(new AmqpConnectException(new ConnectException("sin broker"))).when(template)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertFalse(sender.enviarConfirmado(EVENTO));
    }

    private void confirmarCon(boolean ack) {
        doAnswer(invocacion -> {
            CorrelationData correlacion = invocacion.getArgument(3);
            correlacion.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
