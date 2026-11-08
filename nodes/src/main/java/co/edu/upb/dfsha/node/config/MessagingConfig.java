package co.edu.upb.dfsha.node.config;

import co.edu.upb.dfsha.node.adapter.amqp.AmqpEventPublisher;
import co.edu.upb.dfsha.node.adapter.amqp.AmqpMessageSender;
import co.edu.upb.dfsha.node.adapter.amqp.BrokerReconnectListener;
import co.edu.upb.dfsha.node.adapter.amqp.CommandListener;
import co.edu.upb.dfsha.node.adapter.amqp.CommandParser;
import co.edu.upb.dfsha.node.adapter.amqp.OutboxDrainer;
import co.edu.upb.dfsha.node.adapter.amqp.OutboxStore;
import co.edu.upb.dfsha.node.adapter.grpc.ActiveCallsInterceptor;
import co.edu.upb.dfsha.node.adapter.grpc.GrpcBlockReplicator;
import co.edu.upb.dfsha.node.adapter.grpc.GrpcReplicaClient;
import co.edu.upb.dfsha.node.adapter.grpc.TransferRateLimiter;
import co.edu.upb.dfsha.node.application.BlockReportService;
import co.edu.upb.dfsha.node.application.CommandDispatcher;
import co.edu.upb.dfsha.node.application.DeleteBlockService;
import co.edu.upb.dfsha.node.application.HeartbeatService;
import co.edu.upb.dfsha.node.application.ReplicateBlockService;
import co.edu.upb.dfsha.node.application.ScrubberService;
import co.edu.upb.dfsha.node.domain.DeleteBlockCommand;
import co.edu.upb.dfsha.node.domain.NodeIdentity;
import co.edu.upb.dfsha.node.domain.ReplicateBlockCommand;
import co.edu.upb.dfsha.node.domain.ResyncCommand;
import co.edu.upb.dfsha.node.port.BlockStore;
import co.edu.upb.dfsha.node.port.EventPublisher;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;

/** RabbitMQ: eventos con outbox, comandos del servidor y los servicios que los usan. */
@Configuration
public class MessagingConfig {

    private static final Duration REINTENTO_OUTBOX = Duration.ofSeconds(5);
    private static final Duration TOPE_REPLICACION = Duration.ofMinutes(10);

    @Bean
    AmqpMessageSender enviador(RabbitTemplate template, NodeProperties props) {
        return new AmqpMessageSender(template, props.nodeId());
    }

    @Bean
    OutboxStore outbox(NodeProperties props, JsonMapper json) {
        return new OutboxStore(props.dataDir().resolve("outbox"), json);
    }

    /** Un único hilo: es lo que garantiza que los eventos salgan en orden. */
    @Bean
    OutboxDrainer drenadorOutbox(OutboxStore outbox, AmqpMessageSender enviador) {
        var hilo = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("outbox").factory());
        var drenador = new OutboxDrainer(outbox, enviador, hilo);
        drenador.iniciar(REINTENTO_OUTBOX);
        return drenador;
    }

    @Bean
    EventPublisher publicadorEventos(AmqpMessageSender enviador, OutboxStore outbox, OutboxDrainer drenador,
                                     MessageConverter conversor) {
        return new AmqpEventPublisher(enviador, outbox, drenador::avisar, conversor);
    }

    @Bean
    HeartbeatService latidos(NodeProperties props, BlockStore almacen, EventPublisher eventos,
                             ActiveCallsInterceptor contador, Clock reloj) {
        return new HeartbeatService(props.nodeId(), almacen, eventos, contador, reloj);
    }

    @Bean
    BlockReportService reportes(NodeIdentity identidad, BlockStore almacen, EventPublisher eventos) {
        return new BlockReportService(identidad, almacen, eventos);
    }

    @Bean
    ScrubberService scrubber(NodeProperties props, BlockStore almacen, EventPublisher eventos,
                             TransferRateLimiter limitador) {
        return new ScrubberService(props.nodeId(), almacen, eventos, limitador);
    }

    @Bean
    CommandDispatcher despachador(NodeProperties props, BlockStore almacen, EventPublisher eventos,
                                  GrpcReplicaClient replicas, TransferRateLimiter limitador,
                                  BlockReportService reportes) {
        var replicador = new GrpcBlockReplicator(replicas, limitador, TOPE_REPLICACION);
        return new CommandDispatcher()
                .registrar(ReplicateBlockCommand.class, new ReplicateBlockService(almacen, replicador))
                .registrar(DeleteBlockCommand.class, new DeleteBlockService(props.nodeId(), almacen, eventos))
                .registrar(ResyncCommand.class, comando -> reportes.publicarTodo());
    }

    @Bean
    CommandListener escuchaComandos(JsonMapper json, CommandDispatcher despachador) {
        return new CommandListener(new CommandParser(json), despachador);
    }

    /**
     * La primera conexión (al arrancar) y cada reconexión envían el registro y el block report:
     * así el servidor conoce al nodo aunque haya perdido eventos mientras el broker estuvo caído.
     */
    @Bean
    BrokerReconnectListener reconexion(CachingConnectionFactory conexiones, BlockReportService reportes) {
        var hilo = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("reconexion").factory());
        var listener = new BrokerReconnectListener(reportes::publicarTodo, hilo);
        conexiones.addConnectionListener(listener);
        return listener;
    }
}
