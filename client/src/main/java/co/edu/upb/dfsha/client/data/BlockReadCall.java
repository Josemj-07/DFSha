package co.edu.upb.dfsha.client.data;

import co.edu.upb.dfsha.client.integrity.Checksums;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/** Una llamada LeerBloque. Pide un fragmento a la vez, para que el disco local marque el ritmo. */
final class BlockReadCall implements ClientResponseObserver<ReadRequest, Chunk> {

    private final ByteRange range;
    private final ChunkConsumer consumer;
    private final CompletableFuture<Void> result = new CompletableFuture<>();
    private final IdleWatchdog watchdog;
    private ClientCallStreamObserver<ReadRequest> stream;
    private long expectedOffset;

    BlockReadCall(ByteRange range, ChunkConsumer consumer, Duration inactivity, ScheduledExecutorService scheduler) {
        this.range = range;
        this.consumer = consumer;
        this.expectedOffset = range.offset();
        this.watchdog = new IdleWatchdog(inactivity, () -> cancelar("nodo sin respuesta"));
        watchdog.iniciar(scheduler);
        result.whenComplete((value, error) -> watchdog.detener());
    }

    CompletableFuture<Void> obtenerResultado() {
        return result;
    }

    void cancelar(String reason) {
        if (stream != null) {
            stream.cancel(reason, null);
        }
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<ReadRequest> requestStream) {
        this.stream = requestStream;
        requestStream.disableAutoRequestWithInitial(1);
    }

    @Override
    public void onNext(Chunk chunk) {
        watchdog.registrarAvance();
        try {
            ByteBuffer data = chunk.getData().asReadOnlyByteBuffer();
            verificar(chunk, data);
            consumer.aceptar(chunk.getOffset(), data);
            expectedOffset += chunk.getData().size();
            stream.request(1);
        } catch (CorruptReplicaException e) {
            abortar(e);
        } catch (IOException e) {
            abortar(new UncheckedIOException("Error de escritura local", e));
        }
    }

    private void verificar(Chunk chunk, ByteBuffer data) {
        if (chunk.getOffset() != expectedOffset) {
            throw new CorruptReplicaException("Offset inesperado: " + chunk.getOffset());
        }
        if (Checksums.calcularCrc32c(data) != chunk.getCrc32C()) {
            throw new CorruptReplicaException("CRC32C incorrecto en el offset " + chunk.getOffset());
        }
    }

    private void abortar(RuntimeException error) {
        result.completeExceptionally(error);
        stream.cancel("lectura abortada", error);
    }

    @Override
    public void onCompleted() {
        long received = expectedOffset - range.offset();
        if (range.length() > 0 && received != range.length()) {
            result.completeExceptionally(new CorruptReplicaException(
                    "Lectura incompleta: " + received + " de " + range.length() + " bytes"));
            return;
        }
        result.complete(null);
    }

    @Override
    public void onError(Throwable error) {
        Status status = Status.fromThrowable(error);
        RuntimeException failure = status.getCode() == Status.Code.DATA_LOSS
                ? new CorruptReplicaException("La réplica reportó datos dañados")
                : new ReplicaReadException("Falló la lectura: " + status.getCode(), error);
        result.completeExceptionally(failure);
    }
}
