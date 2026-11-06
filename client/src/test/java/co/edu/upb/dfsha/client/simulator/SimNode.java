package co.edu.upb.dfsha.client.simulator;

import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import co.edu.upb.dfsha.proto.v1.ReplicationServiceGrpc;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.TlsChannelCredentials;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettySslContextServerCredentials;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

/** DataNode simulado: guarda bloques en disco y sirve gRPC con TLS 1.3 obligatorio. */
public final class SimNode implements AutoCloseable {

    /** Fallas que se pueden inyectar. */
    public enum Failure { NONE, ABORT_WRITE, OMIT_RECEIPT, CORRUPT_READ, BAD_CRC_READ, HANG_READ, REFUSE_READ }

    static final Context.Key<String> TOKEN = Context.key("sim-token");
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final int CHUNK = 1024 * 1024;

    private final String tlsVersion;
    private final String nodeId;
    private final String az;
    private final SimPki.Identity identity;
    private final Path caCert;
    private final Path dir;
    private final Map<String, ManagedChannel> peers = new ConcurrentHashMap<>();
    private volatile Failure failure = Failure.NONE;
    private volatile int failuresLeft = Integer.MAX_VALUE;
    private Server clientServer;
    private Server replicationServer;
    private int clientPort;
    private int replicationPort;

    public SimNode(String nodeId, String az, SimPki.Identity identity, Path caCert, Path dir) throws IOException {
        this(nodeId, az, identity, caCert, dir, "TLSv1.3");
    }

    public SimNode(String nodeId, String az, SimPki.Identity identity, Path caCert, Path dir, String tlsVersion)
            throws IOException {
        this.tlsVersion = tlsVersion;
        this.nodeId = nodeId;
        this.az = az;
        this.identity = identity;
        this.caCert = caCert;
        this.dir = dir;
        Files.createDirectories(dir);
        iniciar();
    }

    public void iniciar() throws IOException {
        clientServer = crearServidor(clientPort, new BlockApi());
        replicationServer = crearServidor(replicationPort, new ReplicationApi());
        clientPort = clientServer.getPort();
        replicationPort = replicationServer.getPort();
    }

    private Server crearServidor(int port, io.grpc.BindableService service) throws IOException {
        var ssl = GrpcSslContexts.configure(
                SslContextBuilder.forServer(identity.certFile().toFile(), identity.keyFile().toFile()))
                .protocols(tlsVersion).build();
        return Grpc.newServerBuilderForPort(port, NettySslContextServerCredentials.create(ssl))
                .maxInboundMetadataSize(256 * 1024)
                .addService(ServerInterceptors.intercept(service, new TokenCapture()))
                .build().start();
    }

    public void detener() throws InterruptedException {
        clientServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        replicationServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    public void inyectar(Failure value, int times) {
        failuresLeft = times;
        failure = value;
    }

    private synchronized boolean consumir(Failure value) {
        if (failure != value || failuresLeft <= 0) {
            return false;
        }
        failuresLeft--;
        return true;
    }

    public String obtenerId() {
        return nodeId;
    }

    public String obtenerZona() {
        return az;
    }

    public String obtenerDireccionCliente() {
        return "localhost:" + clientPort;
    }

    public String obtenerDireccionReplicacion() {
        return "localhost:" + replicationPort;
    }

    public java.security.PublicKey obtenerClavePublica() {
        return identity.keys().getPublic();
    }

    public boolean tieneBloque(String blockId, long gen) {
        return Files.exists(rutaBloque(blockId, gen));
    }

    Path rutaBloque(String blockId, long gen) {
        return dir.resolve(blockId + "_" + gen + ".blk");
    }

    java.security.PrivateKey obtenerClavePrivada() {
        return identity.keys().getPrivate();
    }

    boolean debeAbortar() {
        return consumir(Failure.ABORT_WRITE);
    }

    boolean debeOmitirRecibo() {
        return consumir(Failure.OMIT_RECEIPT);
    }

    ManagedChannel obtenerCanal(String addr) {
        return peers.computeIfAbsent(addr, key -> {
            try {
                int split = key.lastIndexOf(':');
                var credentials = TlsChannelCredentials.newBuilder().trustManager(caCert.toFile()).build();
                return Grpc.newChannelBuilderForAddress(key.substring(0, split),
                        Integer.parseInt(key.substring(split + 1)), credentials).build();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public void close() throws InterruptedException {
        peers.values().forEach(ManagedChannel::shutdownNow);
        detener();
    }

    private final class TokenCapture implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                           ServerCallHandler<Q, R> next) {
            String header = headers.get(AUTHORIZATION);
            if (header == null || !header.startsWith("Bearer " + SimTokens.PREFIX)) {
                call.close(Status.UNAUTHENTICATED.withDescription("token inválido"), new Metadata());
                return new ServerCall.Listener<>() { };
            }
            return Contexts.interceptCall(Context.current().withValue(TOKEN, header.substring(7)), call, headers, next);
        }
    }

    private final class BlockApi extends BlockServiceGrpc.BlockServiceImplBase {
        @Override
        public StreamObserver<WriteRequest> escribirBloque(StreamObserver<WriteResponse> responses) {
            return new SimWriteSession(SimNode.this, (ServerCallStreamObserver<WriteResponse>) responses, TOKEN.get());
        }

        @Override
        public void leerBloque(ReadRequest request, StreamObserver<Chunk> responses) {
            if (!SimTokens.permite(TOKEN.get(), "r", request.getBlockId(), request.getGen())) {
                responses.onError(Status.PERMISSION_DENIED.asRuntimeException());
                return;
            }
            if (consumir(Failure.REFUSE_READ)) {
                responses.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }
            if (consumir(Failure.HANG_READ)) {
                return;
            }
            Path file = rutaBloque(request.getBlockId(), request.getGen());
            if (!Files.exists(file)) {
                responses.onError(Status.NOT_FOUND.asRuntimeException());
                return;
            }
            enviarBloque(file, request, responses);
        }

        private void enviarBloque(Path file, ReadRequest request, StreamObserver<Chunk> responses) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                long position = request.getOffset();
                long end = request.getLength() == 0 ? channel.size()
                        : Math.min(channel.size(), position + request.getLength());
                boolean corrupt = consumir(Failure.CORRUPT_READ);
                boolean badCrc = consumir(Failure.BAD_CRC_READ);
                while (position < end) {
                    ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(CHUNK, end - position));
                    channel.read(buffer, position);
                    byte[] data = buffer.array();
                    if (corrupt) {
                        data[0] ^= 0x55;
                    }
                    CRC32C crc = new CRC32C();
                    crc.update(data);
                    responses.onNext(Chunk.newBuilder().setOffset(position).setData(ByteString.copyFrom(data))
                            .setCrc32C((int) crc.getValue() + (badCrc ? 1 : 0)).build());
                    position += data.length;
                }
                responses.onCompleted();
            } catch (IOException e) {
                responses.onError(Status.INTERNAL.withCause(e).asRuntimeException());
            }
        }
    }

    private final class ReplicationApi extends ReplicationServiceGrpc.ReplicationServiceImplBase {
        @Override
        public StreamObserver<WriteRequest> replicarBloque(StreamObserver<WriteResponse> responses) {
            return new SimWriteSession(SimNode.this, (ServerCallStreamObserver<WriteResponse>) responses, TOKEN.get());
        }
    }
}
