package co.edu.upb.dfsha.node.config;

import co.edu.upb.dfsha.node.adapter.grpc.ActiveCallsInterceptor;
import co.edu.upb.dfsha.node.adapter.grpc.AuthInterceptor;
import co.edu.upb.dfsha.node.adapter.grpc.BlockGrpcService;
import co.edu.upb.dfsha.node.adapter.grpc.GrpcReplicaClient;
import co.edu.upb.dfsha.node.adapter.grpc.ReplicationGrpcService;
import co.edu.upb.dfsha.node.adapter.grpc.WriteSessionFactory;
import co.edu.upb.dfsha.node.adapter.crypto.EcdsaReceiptSigner;
import co.edu.upb.dfsha.node.adapter.crypto.JwtTokenVerifier;
import co.edu.upb.dfsha.node.adapter.storage.BlockStoreRecovery;
import co.edu.upb.dfsha.node.application.ReadBlockService;
import co.edu.upb.dfsha.node.port.BlockStore;
import co.edu.upb.dfsha.node.port.EventPublisher;
import io.grpc.BindableService;
import io.grpc.ServerInterceptors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

/** Servidores gRPC (50051 y 50052) y cliente hacia otros nodos. */
@Configuration
public class GrpcConfig {

    private static final Duration INACTIVIDAD_CADENA = Duration.ofSeconds(5);
    // Menor que la espera del cliente (30 s): así este nodo alcanza a decir qué nodo falló.
    private static final Duration CONFIRMACION_CADENA = Duration.ofSeconds(20);

    @Bean(destroyMethod = "close")
    GrpcReplicaClient clienteReplicas(NodeProperties props) {
        // No es un bean propio: Spring usaría un ScheduledExecutorService registrado para @Scheduled.
        var vigilancia = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("vigilancia-cadena").factory());
        return GrpcReplicaClient.conTls(props.obtenerCa(), vigilancia, INACTIVIDAD_CADENA, CONFIRMACION_CADENA);
    }

    @Bean
    WriteSessionFactory fabricaEscrituras(NodeProperties props, BlockStore almacen, EcdsaReceiptSigner firma,
                                          GrpcReplicaClient replicas, EventPublisher eventos) {
        return new WriteSessionFactory(props.nodeId(), almacen, firma, replicas, eventos);
    }

    @Bean
    ActiveCallsInterceptor contadorLlamadas() {
        return new ActiveCallsInterceptor();
    }

    /**
     * @param recuperacion se pide solo para garantizar el orden: el disco se limpia antes de que el
     *                     nodo acepte conexiones
     */
    @Bean
    GrpcServerLifecycle servidoresGrpc(NodeProperties props, JwtTokenVerifier tokens, BlockStore almacen,
                                       WriteSessionFactory escrituras, ActiveCallsInterceptor contador,
                                       BlockStoreRecovery recuperacion) {
        var clientes = new BlockGrpcService(new ReadBlockService(almacen), escrituras);
        var replicacion = new ReplicationGrpcService(escrituras);
        return new GrpcServerLifecycle(props.obtenerCertificado(), props.obtenerClavePrivada(),
                new AuthInterceptor(tokens), List.of(
                new GrpcEndpoint("clientes", props.clientPort(), List.of(contar(clientes, contador))),
                new GrpcEndpoint("replicacion", props.replicationPort(), List.of(contar(replicacion, contador)))));
    }

    private static BindableService contar(BindableService servicio, ActiveCallsInterceptor contador) {
        return () -> ServerInterceptors.intercept(servicio, contador);
    }
}
