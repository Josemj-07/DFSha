package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.port.ReplicaClient;
import co.edu.upb.dfsha.node.port.ReplicaListener;
import co.edu.upb.dfsha.node.port.ReplicaStream;
import co.edu.upb.dfsha.proto.v1.Downstream;
import co.edu.upb.dfsha.proto.v1.ReplicationServiceGrpc;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.MetadataUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Cliente gRPC hacia otros nodos. Reutiliza un canal por dirección: abrir una conexión TLS por
 * bloque sería mucho más lento.
 */
public class GrpcReplicaClient implements ReplicaClient, AutoCloseable {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final Function<String, ManagedChannel> fabricaCanales;
    private final ScheduledExecutorService scheduler;
    private final Duration inactividad;
    private final Duration confirmacion;
    private final Map<String, ManagedChannel> canales = new ConcurrentHashMap<>();

    /**
     * @param inactividad tiempo máximo sin avance mientras el siguiente nodo no acepta datos
     * @param confirmacion espera tras el último mensaje; menor que la del cliente (30 s) para que
     *                     este nodo alcance a responder qué nodo falló
     */
    public GrpcReplicaClient(Function<String, ManagedChannel> fabricaCanales, ScheduledExecutorService scheduler,
                             Duration inactividad, Duration confirmacion) {
        this.fabricaCanales = fabricaCanales;
        this.scheduler = scheduler;
        this.inactividad = inactividad;
        this.confirmacion = confirmacion;
    }

    /** Versión de producción: TLS 1.3 validando el certificado del otro nodo con la CA. */
    public static GrpcReplicaClient conTls(Path caFile, ScheduledExecutorService scheduler, Duration inactividad,
                                           Duration confirmacion) {
        ChannelCredentials credenciales = crearCredenciales(caFile);
        return new GrpcReplicaClient(direccion -> abrirCanal(direccion, credenciales), scheduler, inactividad,
                confirmacion);
    }

    @Override
    public ReplicaStream abrir(Downstream target, String token, ReplicaListener listener) {
        ManagedChannel canal = canales.computeIfAbsent(target.getAddr(), fabricaCanales);
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + token);
        var stream = new GrpcReplicaStream(target.getNodeId(), listener, scheduler, inactividad, confirmacion);
        ReplicationServiceGrpc.newStub(canal)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .replicarBloque(stream);
        return stream;
    }

    @Override
    public void close() {
        for (ManagedChannel canal : canales.values()) {
            canal.shutdown();
            try {
                canal.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            canal.shutdownNow();
        }
        canales.clear();
    }

    static ManagedChannel abrirCanal(String direccion, ChannelCredentials credenciales) {
        int separador = direccion.lastIndexOf(':');
        if (separador <= 0) {
            throw new IllegalArgumentException("dirección de nodo inválida: " + direccion);
        }
        int puerto = Integer.parseInt(direccion.substring(separador + 1));
        return Grpc.newChannelBuilderForAddress(direccion.substring(0, separador), puerto, credenciales)
                .maxInboundMetadataSize(256 * 1024)
                .build();
    }

    private static ChannelCredentials crearCredenciales(Path caFile) {
        try {
            return TlsChannelCredentials.newBuilder().trustManager(caFile.toFile()).build();
        } catch (IOException e) {
            throw new UncheckedIOException("no se pudo leer la CA", e);
        }
    }
}
