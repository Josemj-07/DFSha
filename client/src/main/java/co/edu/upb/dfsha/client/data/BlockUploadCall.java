package co.edu.upb.dfsha.client.data;

import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.Downstream;
import co.edu.upb.dfsha.proto.v1.WriteHeader;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import co.edu.upb.dfsha.proto.v1.WriteTrailer;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/** Una llamada EscribirBloque. Solo envía cuando el stream está listo, para no llenar la memoria. */
final class BlockUploadCall implements ClientResponseObserver<WriteRequest, WriteResponse> {

    private static final Metadata.Key<String> FAILED_NODES =
            Metadata.Key.of("dfs-failed-nodes", Metadata.ASCII_STRING_MARSHALLER);

    private final BlockTarget target;
    private final BlockSource source;
    private final Duration confirmationLimit;
    private final CompletableFuture<List<Receipt>> result = new CompletableFuture<>();
    private final List<Receipt> receipts = new ArrayList<>();
    private final IdleWatchdog watchdog;
    private ClientCallStreamObserver<WriteRequest> stream;
    private boolean headerSent;
    private boolean finished;

    BlockUploadCall(BlockTarget target, BlockSource source, GrpcTimeouts timeouts, ScheduledExecutorService scheduler) {
        this.target = target;
        this.source = source;
        this.confirmationLimit = timeouts.confirmation();
        this.watchdog = new IdleWatchdog(timeouts.inactivity(), () -> cancelar("nodo sin respuesta"));
        watchdog.iniciar(scheduler);
        result.whenComplete((value, error) -> liberar(error));
    }

    CompletableFuture<List<Receipt>> obtenerResultado() {
        return result;
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<WriteRequest> requestStream) {
        this.stream = requestStream;
        requestStream.setOnReadyHandler(this::enviar);
    }

    private void enviar() {
        try {
            if (!headerSent && stream.isReady()) {
                stream.onNext(WriteRequest.newBuilder().setHeader(construirEncabezado()).build());
                headerSent = true;
            }
            while (headerSent && stream.isReady() && source.tieneSiguiente()) {
                stream.onNext(construirFragmento(source.siguiente()));
                watchdog.registrarAvance();
            }
            if (headerSent && !finished && !source.tieneSiguiente()) {
                terminarEnvio();
            }
        } catch (RuntimeException e) {
            stream.cancel("fallo local al leer el bloque", e);
            result.completeExceptionally(e);
        }
    }

    private void terminarEnvio() {
        finished = true;
        WriteTrailer trailer = WriteTrailer.newBuilder().setSha256(source.obtenerSha256()).build();
        stream.onNext(WriteRequest.newBuilder().setTrailer(trailer).build());
        stream.onCompleted();
        watchdog.cambiarLimite(confirmationLimit);
    }

    private WriteHeader construirEncabezado() {
        WriteHeader.Builder header = WriteHeader.newBuilder()
                .setBlockId(target.blockId())
                .setGen(target.gen())
                .setLength(target.length());
        target.downstream().forEach(hop -> header.addDownstream(
                Downstream.newBuilder().setNodeId(hop.nodeId()).setAddr(hop.replicationAddr())));
        return header.build();
    }

    private WriteRequest construirFragmento(BlockSource.Fragment fragment) {
        Chunk chunk = Chunk.newBuilder()
                .setOffset(fragment.offset())
                .setData(ByteString.copyFrom(fragment.data()))
                .setCrc32C(fragment.crc32c())
                .build();
        return WriteRequest.newBuilder().setChunk(chunk).build();
    }

    @Override
    public void onNext(WriteResponse response) {
        response.getReceiptsList().forEach(receipt -> receipts.add(new Receipt(receipt.getNodeId(),
                receipt.getBlockId(), receipt.getGen(), receipt.getLength(), receipt.getSha256(),
                receipt.getSignature().toByteArray())));
    }

    @Override
    public void onCompleted() {
        result.complete(List.copyOf(receipts));
    }

    @Override
    public void onError(Throwable error) {
        Status status = Status.fromThrowable(error);
        result.completeExceptionally(new ReplicaWriteException(status.getCode(), obtenerFallidos(error),
                "Falló la escritura del bloque " + target.blockId() + ": " + status.getCode(), error));
    }

    private List<String> obtenerFallidos(Throwable error) {
        Metadata trailers = Status.trailersFromThrowable(error);
        String value = trailers == null ? null : trailers.get(FAILED_NODES);
        if (value == null || value.isBlank()) {
            return new ArrayList<>(List.of(target.firstNode()));
        }
        return Arrays.asList(value.split(","));
    }

    private void cancelar(String reason) {
        if (stream != null) {
            stream.cancel(reason, null);
        }
    }

    private void liberar(Throwable error) {
        watchdog.detener();
        source.close();
        if (error instanceof java.util.concurrent.CancellationException) {
            cancelar("transferencia cancelada");
        }
    }
}
