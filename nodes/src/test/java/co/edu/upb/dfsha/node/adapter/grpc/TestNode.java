package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.adapter.crypto.EcdsaReceiptSigner;
import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.application.ReadBlockService;
import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.InvalidTokenException;
import co.edu.upb.dfsha.node.domain.Op;
import co.edu.upb.dfsha.node.port.EventPublisher;
import co.edu.upb.dfsha.node.port.TokenVerifier;
import co.edu.upb.dfsha.proto.v1.Downstream;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Un DataNode completo dentro de la prueba: almacén real, firma real y servidor gRPC en proceso. */
final class TestNode implements AutoCloseable {

    /** Doble del verificador: el token es "<ops>:<bloque>", por ejemplo "w:blk_01:1". */
    static final TokenVerifier TOKENS = token -> {
        String[] partes = token.split(":", 2);
        if (partes.length != 2) {
            throw new InvalidTokenException("token inválido");
        }
        Set<Op> ops = partes[0].equals("r") ? Set.of(Op.READ) : Set.of(Op.WRITE);
        return new BlockGrant("jose", ops, Set.of(partes[1]));
    };

    final String id;
    final String direccion;
    final Path dataDir;
    final FileSystemBlockStore store;
    final KeyPair claves = generarClaves();
    final List<String> eventos = new CopyOnWriteArrayList<>();
    private final GrpcReplicaClient replicas;
    private final Server servidor;

    TestNode(String id, Path base, ScheduledExecutorService scheduler, Duration inactividad, Duration confirmacion)
            throws IOException {
        this.id = id;
        this.direccion = id + "-" + UUID.randomUUID();
        this.dataDir = base.resolve(id);
        this.store = new FileSystemBlockStore(new BlockPaths(dataDir), new MetaFileCodec(), 0);
        this.replicas = new GrpcReplicaClient(dir -> InProcessChannelBuilder.forName(dir).build(), scheduler,
                inactividad, confirmacion);
        var fabrica = new WriteSessionFactory(id, store, new EcdsaReceiptSigner(id, claves.getPrivate()), replicas,
                registrador());
        var auth = new AuthInterceptor(TOKENS);
        this.servidor = InProcessServerBuilder.forName(direccion)
                .addService(ServerInterceptors.intercept(new BlockGrpcService(new ReadBlockService(store), fabrica), auth))
                .addService(ServerInterceptors.intercept(new ReplicationGrpcService(fabrica), auth))
                .build().start();
    }

    Downstream comoDestino() {
        return Downstream.newBuilder().setNodeId(id).setAddr(direccion).build();
    }

    long contarArchivos(String extension) throws IOException {
        if (!Files.exists(dataDir)) {
            return 0;
        }
        try (Stream<Path> archivos = Files.walk(dataDir)) {
            return archivos.filter(p -> p.getFileName().toString().endsWith(extension)).count();
        }
    }

    @Override
    public void close() throws InterruptedException {
        replicas.close();
        servidor.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private EventPublisher registrador() {
        return new EventPublisher() {
            @Override
            public void publicarDurable(String routingKey, Object event) {
                eventos.add(routingKey);
            }

            @Override
            public void publicarVolatil(String routingKey, Object event) {
                eventos.add(routingKey);
            }
        };
    }

    private static KeyPair generarClaves() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("EC");
            generador.initialize(new ECGenParameterSpec("secp256r1"));
            return generador.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
