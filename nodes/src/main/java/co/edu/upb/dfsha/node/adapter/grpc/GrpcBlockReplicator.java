package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.ReplicationFailedException;
import co.edu.upb.dfsha.node.port.BlockReplicator;
import co.edu.upb.dfsha.node.port.ReplicaClient;
import co.edu.upb.dfsha.node.port.ReplicaFailure;
import co.edu.upb.dfsha.node.port.ReplicaListener;
import co.edu.upb.dfsha.node.port.ReplicaStream;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.Downstream;
import co.edu.upb.dfsha.proto.v1.WriteHeader;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import co.edu.upb.dfsha.proto.v1.WriteTrailer;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.CRC32C;

/**
 * Envía un bloque local al puerto 50052 de otro nodo, como lo haría un cliente: header, chunks
 * de 1 MiB con CRC32C y trailer con el SHA-256. Corre en el hilo del comando y respeta el
 * control de flujo del destino.
 */
public class GrpcBlockReplicator implements BlockReplicator {

    private final ReplicaClient replicas;
    private final TransferRateLimiter limitador;
    private final Duration esperaMaxima;

    /** @param esperaMaxima tope de toda la copia; el vigilante del stream detecta antes un destino colgado */
    public GrpcBlockReplicator(ReplicaClient replicas, TransferRateLimiter limitador, Duration esperaMaxima) {
        this.replicas = replicas;
        this.limitador = limitador;
        this.esperaMaxima = esperaMaxima;
    }

    @Override
    public void replicar(BlockMeta meta, ReadableByteChannel datos, String targetNodeId, String targetAddr,
                         String token) {
        var escucha = new Escucha();
        var destino = Downstream.newBuilder().setNodeId(targetNodeId).setAddr(targetAddr).build();
        ReplicaStream stream = replicas.abrir(destino, token, escucha);
        try {
            stream.enviar(WriteRequest.newBuilder().setHeader(WriteHeader.newBuilder()
                    .setBlockId(meta.key().blockId()).setGen(meta.key().gen()).setLength(meta.length())).build());
            enviarDatos(meta, datos, stream, escucha);
            if (!escucha.resultado.isDone()) {
                stream.enviar(WriteRequest.newBuilder()
                        .setTrailer(WriteTrailer.newBuilder().setSha256(meta.sha256())).build());
                stream.completar();
            }
            esperarResultado(escucha, meta, targetNodeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stream.cancelar("replicación interrumpida");
            throw new ReplicationFailedException("replicación interrumpida");
        } catch (RuntimeException e) {
            stream.cancelar("falló la replicación");
            throw e;
        }
    }

    private void enviarDatos(BlockMeta meta, ReadableByteChannel datos, ReplicaStream stream, Escucha escucha)
            throws InterruptedException {
        ByteBuffer buffer = ByteBuffer.allocate(BlockGrpcService.TAMANIO_CHUNK);
        long offset = 0;
        while (offset < meta.length() && !escucha.resultado.isDone()) {
            escucha.esperarListo(stream);
            int tamanio = leer(datos, buffer, (int) Math.min(buffer.capacity(), meta.length() - offset));
            limitador.adquirir(tamanio);
            ByteString parte = ByteString.copyFrom(buffer);
            CRC32C crc = new CRC32C();
            crc.update(parte.asReadOnlyByteBuffer());
            stream.enviar(WriteRequest.newBuilder().setChunk(Chunk.newBuilder().setOffset(offset).setData(parte)
                    .setCrc32C((int) crc.getValue())).build());
            offset += tamanio;
        }
    }

    private void esperarResultado(Escucha escucha, BlockMeta meta, String targetNodeId) throws InterruptedException {
        ReplicaFailure falla;
        try {
            falla = escucha.resultado.get(esperaMaxima.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new ReplicationFailedException("sin respuesta de " + targetNodeId);
        }
        if (falla != null && !falla.yaExistia()) {
            throw new ReplicationFailedException("no se pudo copiar " + meta.key().obtenerAlcance() + " a "
                    + targetNodeId + ": " + falla.codigo());
        }
    }

    private static int leer(ReadableByteChannel datos, ByteBuffer buffer, int tamanio) {
        buffer.clear().limit(tamanio);
        try {
            while (buffer.hasRemaining()) {
                if (datos.read(buffer) < 0) {
                    throw new IllegalStateException("el bloque local terminó antes de lo esperado");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        buffer.flip();
        return tamanio;
    }

    /** Recibe los avisos del destino. resultado termina con null si salió bien o con la falla. */
    private static final class Escucha implements ReplicaListener {

        final CompletableFuture<ReplicaFailure> resultado = new CompletableFuture<>();
        private final Object monitor = new Object();

        void esperarListo(ReplicaStream stream) throws InterruptedException {
            synchronized (monitor) {
                while (!stream.estaListo() && !resultado.isDone()) {
                    // Se despierta también cada segundo por si el aviso llegó entre la comprobación y el wait.
                    monitor.wait(1000);
                }
            }
        }

        @Override
        public void alEstarListo() {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        @Override
        public void alResponder(WriteResponse respuesta) {
            // El recibo del destino no se usa aquí: el destino avisa al servidor con block.received.
        }

        @Override
        public void alTerminar() {
            resultado.complete(null);
            alEstarListo();
        }

        @Override
        public void alFallar(ReplicaFailure falla) {
            resultado.complete(falla);
            alEstarListo();
        }
    }
}
