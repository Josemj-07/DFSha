package co.edu.upb.dfsha.client.simulator;

import co.edu.upb.dfsha.proto.v1.Downstream;
import co.edu.upb.dfsha.proto.v1.Receipt;
import co.edu.upb.dfsha.proto.v1.ReplicationServiceGrpc;
import co.edu.upb.dfsha.proto.v1.WriteHeader;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32C;

/** Recibe un bloque, lo verifica y lo reenvía con control de flujo. Sigue el orden de {@code nodes/README.md}. */
final class SimWriteSession implements StreamObserver<WriteRequest> {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> FAILED_NODES =
            Metadata.Key.of("dfs-failed-nodes", Metadata.ASCII_STRING_MARSHALLER);

    private final SimNode node;
    private final ServerCallStreamObserver<WriteResponse> responses;
    private final String token;
    private final List<Receipt> receipts = new ArrayList<>();
    private final MessageDigest digest;
    private WriteHeader header;
    private FileChannel part;
    private Path partFile;
    private long written;
    private ClientCallStreamObserver<WriteRequest> next;
    private String nextNode;
    private Receipt ownReceipt;
    private boolean nextDone;
    private boolean localDone;
    private boolean requestPending;
    private boolean failed;

    SimWriteSession(SimNode node, ServerCallStreamObserver<WriteResponse> responses, String token) {
        this.node = node;
        this.responses = responses;
        this.token = token;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        responses.disableAutoRequest();
        responses.setOnCancelHandler(this::limpiar);
        responses.request(1);
    }

    @Override
    public synchronized void onNext(WriteRequest request) {
        if (failed) {
            return;
        }
        try {
            switch (request.getPayloadCase()) {
                case HEADER -> abrir(request.getHeader());
                case CHUNK -> escribir(request);
                case TRAILER -> terminar(request);
                default -> throw Status.INVALID_ARGUMENT.asRuntimeException();
            }
            pedirSiguiente();
        } catch (IOException | GeneralSecurityException e) {
            fallar(Status.INTERNAL.withCause(e), null);
        } catch (io.grpc.StatusRuntimeException e) {
            fallar(e.getStatus(), null);
        }
    }

    private void pedirSiguiente() {
        if (next == null || next.isReady()) {
            responses.request(1);
        } else {
            requestPending = true;
        }
    }

    private void abrir(WriteHeader value) throws IOException {
        header = value;
        if (!SimTokens.permite(token, "w", value.getBlockId(), value.getGen())) {
            throw Status.PERMISSION_DENIED.asRuntimeException();
        }
        if (node.debeAbortar()) {
            throw Status.UNAVAILABLE.withDescription("falla inyectada").asRuntimeException();
        }
        partFile = node.rutaBloque(value.getBlockId(), value.getGen()).resolveSibling(
                value.getBlockId() + "_" + value.getGen() + "_" + System.nanoTime() + ".part");
        part = FileChannel.open(partFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (value.getDownstreamCount() > 0) {
            abrirSiguiente(value);
        }
    }

    private void abrirSiguiente(WriteHeader value) {
        Downstream hop = value.getDownstream(0);
        nextNode = hop.getNodeId();
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + token);
        var stub = ReplicationServiceGrpc.newStub(node.obtenerCanal(hop.getAddr()))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        stub.replicarBloque(new ClientResponseObserver<WriteRequest, WriteResponse>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<WriteRequest> stream) {
                next = stream;
                stream.setOnReadyHandler(SimWriteSession.this::cumplirPendiente);
            }

            @Override
            public void onNext(WriteResponse response) {
                synchronized (SimWriteSession.this) {
                    receipts.addAll(response.getReceiptsList());
                }
            }

            @Override
            public void onError(Throwable error) {
                fallar(Status.ABORTED.withDescription("falló la cadena"), nextNode);
            }

            @Override
            public void onCompleted() {
                siguienteTerminado();
            }
        });
        next.onNext(WriteRequest.newBuilder().setHeader(
                value.toBuilder().clearDownstream().addAllDownstream(
                        value.getDownstreamList().subList(1, value.getDownstreamCount()))).build());
    }

    private synchronized void cumplirPendiente() {
        if (requestPending && next.isReady()) {
            requestPending = false;
            responses.request(1);
        }
    }

    private void escribir(WriteRequest request) throws IOException {
        var chunk = request.getChunk();
        CRC32C crc = new CRC32C();
        crc.update(chunk.getData().asReadOnlyByteBuffer());
        if ((int) crc.getValue() != chunk.getCrc32C() || chunk.getOffset() != written) {
            throw Status.DATA_LOSS.withDescription("offset " + chunk.getOffset()).asRuntimeException();
        }
        var data = chunk.getData().asReadOnlyByteBuffer();
        digest.update(data.duplicate());
        while (data.hasRemaining()) {
            written += part.write(data);
        }
        if (next != null) {
            next.onNext(request);
        }
    }

    private void terminar(WriteRequest request) throws IOException, GeneralSecurityException {
        String sha256 = HexFormat.of().formatHex(digest.digest());
        if (written != header.getLength() || !sha256.equals(request.getTrailer().getSha256())) {
            throw Status.DATA_LOSS.withDescription("hash incorrecto").asRuntimeException();
        }
        part.force(true);
        part.close();
        Files.move(partFile, node.rutaBloque(header.getBlockId(), header.getGen()), StandardCopyOption.ATOMIC_MOVE);
        if (!node.debeOmitirRecibo()) {
            ownReceipt = firmar(sha256);
        }
        if (next != null) {
            next.onNext(request);
            next.onCompleted();
        }
        localDone = true;
        responderSiListo();
    }

    private Receipt firmar(String sha256) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(node.obtenerClavePrivada());
        signer.update(SimReceipts.crearContenido(header.getBlockId(), header.getGen(), header.getLength(), sha256,
                node.obtenerId()));
        return Receipt.newBuilder().setNodeId(node.obtenerId()).setBlockId(header.getBlockId())
                .setGen(header.getGen()).setLength(header.getLength()).setSha256(sha256)
                .setSignature(ByteString.copyFrom(signer.sign())).build();
    }

    private synchronized void siguienteTerminado() {
        nextDone = true;
        responderSiListo();
    }

    private void responderSiListo() {
        if (localDone && (next == null || nextDone) && !failed) {
            WriteResponse.Builder response = WriteResponse.newBuilder().addAllReceipts(receipts);
            if (ownReceipt != null) {
                response.addReceipts(ownReceipt);
            }
            responses.onNext(response.build());
            responses.onCompleted();
        }
    }

    private synchronized void fallar(Status status, String failedNode) {
        if (failed) {
            return;
        }
        failed = true;
        limpiar();
        if (next != null) {
            next.cancel("cadena abortada", null);
        }
        Metadata trailers = new Metadata();
        if (failedNode != null) {
            trailers.put(FAILED_NODES, failedNode);
        }
        responses.onError(status.asRuntimeException(trailers));
    }

    private synchronized void limpiar() {
        try {
            if (part != null && part.isOpen()) {
                part.close();
            }
            if (partFile != null) {
                Files.deleteIfExists(partFile);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onError(Throwable error) {
        limpiar();
        if (next != null) {
            next.cancel("cliente canceló", null);
        }
    }

    @Override
    public void onCompleted() {
        // La respuesta sale cuando terminan el nodo local y la cadena.
    }
}
