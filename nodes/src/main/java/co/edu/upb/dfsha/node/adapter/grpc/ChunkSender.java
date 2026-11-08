package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.application.BlockRead;
import co.edu.upb.dfsha.node.domain.DataIntegrityException;
import co.edu.upb.dfsha.proto.v1.Chunk;
import com.google.protobuf.ByteString;
import io.grpc.stub.ServerCallStreamObserver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * Envía un bloque en chunks respetando el control de flujo: solo lee del disco cuando el cliente
 * puede recibir más (isReady). Así un cliente lento no hace que el nodo cargue 128 MiB en memoria.
 */
final class ChunkSender {

    private final ServerCallStreamObserver<Chunk> stream;
    private final BlockRead lectura;
    private final ByteBuffer buffer;
    private long enviados;
    private boolean terminado;

    ChunkSender(ServerCallStreamObserver<Chunk> stream, BlockRead lectura, int tamanioChunk) {
        this.stream = stream;
        this.lectura = lectura;
        this.buffer = ByteBuffer.allocate(tamanioChunk);
    }

    /** Lo llama gRPC cada vez que el cliente vuelve a poder recibir datos. */
    synchronized void enviarMientrasPueda() {
        try {
            while (!terminado && stream.isReady()) {
                if (enviados == lectura.length()) {
                    terminar();
                    stream.onCompleted();
                    return;
                }
                stream.onNext(leerSiguiente());
            }
        } catch (RuntimeException e) {
            terminar();
            stream.onError(GrpcErrorMapper.traducir(e));
        }
    }

    /** El cliente canceló o se desconectó: se libera el archivo sin enviar nada más. */
    synchronized void cancelar() {
        terminar();
    }

    private Chunk leerSiguiente() {
        int tamanio = (int) Math.min(buffer.capacity(), lectura.length() - enviados);
        long offset = lectura.offset() + enviados;
        buffer.clear().limit(tamanio);
        try {
            while (buffer.hasRemaining()) {
                if (lectura.canal().read(buffer) < 0) {
                    throw new DataIntegrityException("el archivo del bloque terminó antes de lo esperado", offset);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        buffer.flip();
        ByteString datos = ByteString.copyFrom(buffer);
        enviados += tamanio;
        return Chunk.newBuilder().setOffset(offset).setData(datos).setCrc32C(calcularCrc(datos)).build();
    }

    private static int calcularCrc(ByteString datos) {
        CRC32C crc = new CRC32C();
        crc.update(datos.asReadOnlyByteBuffer());
        return (int) crc.getValue();
    }

    private void terminar() {
        if (!terminado) {
            terminado = true;
            lectura.close();
        }
    }
}
