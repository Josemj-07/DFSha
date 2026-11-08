package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.domain.BlockEvent;
import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.DataIntegrityException;
import co.edu.upb.dfsha.node.domain.Op;
import co.edu.upb.dfsha.node.domain.PermissionDeniedException;
import co.edu.upb.dfsha.node.domain.Receipt;
import co.edu.upb.dfsha.node.port.BlockWriter;
import co.edu.upb.dfsha.node.port.ReplicaFailure;
import co.edu.upb.dfsha.node.port.ReplicaListener;
import co.edu.upb.dfsha.node.port.ReplicaStream;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.Downstream;
import co.edu.upb.dfsha.proto.v1.WriteHeader;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * Recibe un bloque (header, N chunks, trailer), lo guarda y al mismo tiempo lo reenvía al
 * siguiente nodo. Responde solo cuando el bloque está confirmado aquí y en toda la cadena.
 * Todos los métodos están sincronizados: los mensajes del cliente y los avisos del siguiente
 * nodo llegan por hilos distintos.
 */
final class WriteSession implements StreamObserver<WriteRequest>, ReplicaListener {

    private enum Estado { AWAIT_HEADER, RECEIVING, AWAIT_DOWNSTREAM, DONE }

    static final Metadata.Key<String> FAILED_NODES =
            Metadata.Key.of("dfs-failed-nodes", Metadata.ASCII_STRING_MARSHALLER);
    static final long MAX_LONGITUD_BLOQUE = 128L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(WriteSession.class);

    private final WriteSessionFactory deps;
    private final ServerCallStreamObserver<WriteResponse> responses;
    private final BlockGrant grant;
    private final String token;
    private final List<co.edu.upb.dfsha.proto.v1.Receipt> recibosCadena = new ArrayList<>();
    private Estado estado = Estado.AWAIT_HEADER;
    private BlockKey key;
    private BlockWriter writer;
    private ReplicaStream replica;
    private Receipt reciboPropio;
    private long recibidos;
    private boolean pendiente;
    private boolean cadenaTerminada;

    WriteSession(WriteSessionFactory deps, ServerCallStreamObserver<WriteResponse> responses, BlockGrant grant,
                 String token) {
        this.deps = deps;
        this.responses = responses;
        this.grant = grant;
        this.token = token;
    }

    @Override
    public synchronized void onNext(WriteRequest request) {
        if (estado == Estado.DONE) {
            return;
        }
        try {
            switch (request.getPayloadCase()) {
                case HEADER -> procesarEncabezado(request.getHeader());
                case CHUNK -> procesarChunk(request);
                case TRAILER -> procesarCierre(request);
                default -> throw new IllegalArgumentException("mensaje sin contenido");
            }
            pedirSiguiente();
        } catch (RuntimeException e) {
            fallar(GrpcErrorMapper.traducir(e).getStatus(), List.of());
        }
    }

    /** El cliente cerró su lado. Si no llegó el trailer, el bloque está incompleto. */
    @Override
    public synchronized void onCompleted() {
        if (estado == Estado.AWAIT_HEADER || estado == Estado.RECEIVING) {
            fallar(Status.INVALID_ARGUMENT.withDescription("el stream terminó sin trailer"), List.of());
        }
    }

    @Override
    public void onError(Throwable error) {
        alCancelarCliente();
    }

    synchronized void alCancelarCliente() {
        if (estado != Estado.DONE) {
            estado = Estado.DONE;
            liberar("el cliente canceló la escritura");
        }
    }

    @Override
    public synchronized void alEstarListo() {
        if (pendiente && estado == Estado.RECEIVING && replica.estaListo()) {
            pendiente = false;
            responses.request(1);
        }
    }

    @Override
    public synchronized void alResponder(WriteResponse respuesta) {
        recibosCadena.addAll(respuesta.getReceiptsList());
    }

    @Override
    public synchronized void alTerminar() {
        cadenaTerminada = true;
        responderSiListo();
    }

    @Override
    public void alFallar(ReplicaFailure falla) {
        fallar(Status.ABORTED.withDescription("falló la cadena de réplica"), falla.nodosFallidos());
    }

    private void procesarEncabezado(WriteHeader header) {
        exigirEstado(Estado.AWAIT_HEADER, "header");
        key = new BlockKey(header.getBlockId(), header.getGen());
        if (!grant.permite(Op.WRITE, key)) {
            throw new PermissionDeniedException("el token no permite escribir " + key.obtenerAlcance());
        }
        if (header.getLength() < 0 || header.getLength() > MAX_LONGITUD_BLOQUE) {
            throw new IllegalArgumentException("longitud de bloque inválida: " + header.getLength());
        }
        writer = deps.store().crear(key, header.getLength());
        estado = Estado.RECEIVING;
        if (header.getDownstreamCount() > 0) {
            abrirCadena(header);
        }
    }

    // El siguiente nodo recibe el header sin él mismo en la lista: así cada nodo sabe a quién reenviar.
    private void abrirCadena(WriteHeader header) {
        Downstream siguiente = header.getDownstream(0);
        ReplicaStream abierta = deps.replicas().abrir(siguiente, token, this);
        if (estado == Estado.DONE) {
            abierta.cancelar("la escritura ya terminó");
            return;
        }
        replica = abierta;
        WriteHeader resto = header.toBuilder().clearDownstream()
                .addAllDownstream(header.getDownstreamList().subList(1, header.getDownstreamCount()))
                .build();
        replica.enviar(WriteRequest.newBuilder().setHeader(resto).build());
    }

    private void procesarChunk(WriteRequest request) {
        exigirEstado(Estado.RECEIVING, "chunk");
        Chunk chunk = request.getChunk();
        if (chunk.getData().size() > BlockGrpcService.TAMANIO_CHUNK) {
            throw new IllegalArgumentException("chunk de más de 1 MiB");
        }
        if (chunk.getOffset() != recibidos) {
            throw new DataIntegrityException("offset inesperado " + chunk.getOffset(), recibidos);
        }
        CRC32C crc = new CRC32C();
        crc.update(chunk.getData().asReadOnlyByteBuffer());
        if ((int) crc.getValue() != chunk.getCrc32C()) {
            throw new DataIntegrityException("CRC32C incorrecto", chunk.getOffset());
        }
        writer.escribir(chunk.getData().asReadOnlyByteBuffer());
        recibidos += chunk.getData().size();
        if (replica != null) {
            replica.enviar(request);
        }
    }

    // El trailer se reenvía antes de confirmar aquí: los dos nodos hacen su fsync en paralelo.
    private void procesarCierre(WriteRequest request) {
        exigirEstado(Estado.RECEIVING, "trailer");
        if (replica != null) {
            replica.enviar(request);
            replica.completar();
        }
        BlockMeta meta = writer.confirmar(request.getTrailer().getSha256());
        reciboPropio = deps.signer().firmar(meta);
        estado = Estado.AWAIT_DOWNSTREAM;
        publicarRecibido(meta);
        responderSiListo();
    }

    /** Pide el siguiente mensaje solo si el siguiente nodo puede recibirlo; si no, espera a alEstarListo. */
    private void pedirSiguiente() {
        if (estado == Estado.DONE) {
            return;
        }
        if (estado != Estado.RECEIVING || replica == null || replica.estaListo()) {
            responses.request(1);
        } else {
            pendiente = true;
        }
    }

    private void responderSiListo() {
        if (estado != Estado.AWAIT_DOWNSTREAM || (replica != null && !cadenaTerminada)) {
            return;
        }
        estado = Estado.DONE;
        responses.onNext(WriteResponse.newBuilder()
                .addReceipts(aProto(reciboPropio))
                .addAllReceipts(recibosCadena)
                .build());
        responses.onCompleted();
    }

    private synchronized void fallar(Status status, List<String> nodosFallidos) {
        if (estado == Estado.DONE) {
            return;
        }
        estado = Estado.DONE;
        log.warn("Escritura de {} abortada: {} {}", key == null ? "?" : key.obtenerAlcance(), status.getCode(),
                nodosFallidos);
        liberar("cadena abortada");
        Metadata trailers = new Metadata();
        if (!nodosFallidos.isEmpty()) {
            trailers.put(FAILED_NODES, String.join(",", nodosFallidos));
        }
        responses.onError(status.asRuntimeException(trailers));
    }

    /** Si el bloque ya estaba confirmado aquí, se conserva: el servidor lo borra si nunca lo publica. */
    private void liberar(String motivo) {
        if (replica != null) {
            replica.cancelar(motivo);
        }
        if (writer != null) {
            try {
                writer.abortar();
            } catch (RuntimeException e) {
                log.warn("No se pudo borrar el .part de {}; lo borrará la limpieza periódica", key.obtenerAlcance());
            }
        }
    }

    private void publicarRecibido(BlockMeta meta) {
        try {
            var evento = BlockEvent.de(UUID.randomUUID().toString(), deps.nodeId(), meta);
            deps.eventos().publicarDurable("block.received." + deps.nodeId(), evento);
        } catch (RuntimeException e) {
            // El bloque ya está a salvo; el block report periódico corrige la falta de este evento.
            log.warn("No se pudo publicar block.received de {}", meta.key().obtenerAlcance(), e);
        }
    }

    private void exigirEstado(Estado esperado, String mensaje) {
        if (estado != esperado) {
            throw new IllegalArgumentException("mensaje " + mensaje + " fuera de orden");
        }
    }

    private static co.edu.upb.dfsha.proto.v1.Receipt aProto(Receipt recibo) {
        BlockMeta meta = recibo.meta();
        return co.edu.upb.dfsha.proto.v1.Receipt.newBuilder()
                .setNodeId(recibo.nodeId())
                .setBlockId(meta.key().blockId())
                .setGen(meta.key().gen())
                .setLength(meta.length())
                .setSha256(meta.sha256())
                .setSignature(ByteString.copyFrom(recibo.signature()))
                .build();
    }
}
