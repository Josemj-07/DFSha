package co.edu.upb.dfsha.client.data;

import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import co.edu.upb.dfsha.client.sdk.model.ReplicaRef;
import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/** Adaptador gRPC del plano de datos. */
public final class GrpcBlockGateway implements BlockGateway, AutoCloseable {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final Function<String, Channel> channels;
    private final GrpcTimeouts timeouts;
    private final ScheduledExecutorService scheduler;

    public GrpcBlockGateway(Function<String, Channel> channels, GrpcTimeouts timeouts) {
        this.channels = channels;
        this.timeouts = timeouts;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "dfs-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<List<Receipt>> escribir(BlockTarget target, BlockSource source, String token) {
        var call = new BlockUploadCall(target, source, timeouts, scheduler);
        crearStub(target.firstAddr(), token).escribirBloque(call);
        return call.obtenerResultado();
    }

    @Override
    public void leer(ReplicaRef replica, String blockId, long gen, ByteRange range, String token,
                     ChunkConsumer consumer) {
        var call = new BlockReadCall(range, consumer, timeouts.inactivity(), scheduler);
        ReadRequest request = ReadRequest.newBuilder()
                .setBlockId(blockId).setGen(gen).setOffset(range.offset()).setLength(range.length()).build();
        crearStub(replica.addr(), token).leerBloque(request, call);
        esperar(call);
    }

    private void esperar(BlockReadCall call) {
        try {
            call.obtenerResultado().get();
        } catch (InterruptedException e) {
            call.cancelar("transferencia cancelada");
            Thread.currentThread().interrupt();
            throw new UnavailableException("INTERRUPTED", "Operación interrumpida", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private BlockServiceGrpc.BlockServiceStub crearStub(String addr, String token) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + token);
        return BlockServiceGrpc.newStub(channels.apply(addr))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
