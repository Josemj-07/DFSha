package co.edu.upb.dfsha.client.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.upb.dfsha.client.integrity.Checksums;
import co.edu.upb.dfsha.client.sdk.model.ReplicaRef;
import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import co.edu.upb.dfsha.proto.v1.Receipt;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcBlockGatewayTest {

    private static final ReplicaRef REPLICA = new ReplicaRef("dn-1", "proceso", "az");
    private static final BlockTarget TARGET = new BlockTarget("blk_1", 1, 8, "dn-1", "proceso",
            List.of(new BlockTarget.Hop("dn-2", "dn-2:50052")));
    private static final Metadata.Key<String> FAILED =
            Metadata.Key.of("dfs-failed-nodes", Metadata.ASCII_STRING_MARSHALLER);

    private final List<WriteRequest> received = new ArrayList<>();
    private volatile Consumer<StreamObserver<WriteResponse>> onWriteEnd = this::confirmar;
    private volatile Consumer<StreamObserver<Chunk>> onRead = responses -> { };
    private volatile boolean manualFlow;
    private volatile ServerCallStreamObserver<WriteResponse> writeStream;
    private final CountDownLatch headerSeen = new CountDownLatch(1);
    private Server server;
    private ManagedChannel channel;
    private GrpcBlockGateway gateway;

    private void confirmar(StreamObserver<WriteResponse> responses) {
        responses.onNext(WriteResponse.newBuilder().addReceipts(
                Receipt.newBuilder().setNodeId("dn-1").setBlockId("blk_1").setGen(1).setLength(8).setSha256("aa")).build());
        responses.onCompleted();
    }

    private final class FakeNode extends BlockServiceGrpc.BlockServiceImplBase {
        @Override
        public StreamObserver<WriteRequest> escribirBloque(StreamObserver<WriteResponse> responses) {
            var stream = (ServerCallStreamObserver<WriteResponse>) responses;
            writeStream = stream;
            if (manualFlow) {
                stream.disableAutoRequest();
                stream.request(1);
            }
            return new StreamObserver<>() {
                @Override
                public void onNext(WriteRequest request) {
                    synchronized (received) {
                        received.add(request);
                    }
                    headerSeen.countDown();
                }

                @Override
                public void onError(Throwable error) {
                }

                @Override
                public void onCompleted() {
                    onWriteEnd.accept(responses);
                }
            };
        }

        @Override
        public void leerBloque(ReadRequest request, StreamObserver<Chunk> responses) {
            onRead.accept(responses);
        }
    }

    private static final class CountingSource implements BlockSource {
        final AtomicInteger delivered = new AtomicInteger();
        private final int total;
        private final int size;

        CountingSource(int total, int size) {
            this.total = total;
            this.size = size;
        }

        @Override
        public boolean tieneSiguiente() {
            return delivered.get() < total;
        }

        @Override
        public Fragment siguiente() {
            byte[] data = new byte[size];
            return new Fragment((long) delivered.getAndIncrement() * size, data,
                    Checksums.calcularCrc32c(ByteBuffer.wrap(data)));
        }

        @Override
        public String obtenerSha256() {
            return "aa";
        }

        @Override
        public void close() {
        }
    }

    @BeforeEach
    void iniciar() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(new FakeNode()).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        gateway = new GrpcBlockGateway(addr -> channel,
                new GrpcTimeouts(Duration.ofMillis(300), Duration.ofMillis(300)));
    }

    @AfterEach
    void detener() {
        gateway.close();
        channel.shutdownNow();
        server.shutdownNow();
    }

    private Chunk crearFragmento(long offset, byte[] data) {
        return Chunk.newBuilder().setOffset(offset).setData(ByteString.copyFrom(data))
                .setCrc32C(Checksums.calcularCrc32c(ByteBuffer.wrap(data))).build();
    }

    @Test
    void enviaEncabezadoFragmentosYCierreEnOrden() throws Exception {
        var receipts = gateway.escribir(TARGET, new CountingSource(2, 4), "tok").get(5, TimeUnit.SECONDS);
        assertEquals(1, receipts.size());
        assertEquals("dn-1", receipts.get(0).nodeId());
        assertEquals(4, received.size());
        assertEquals("blk_1", received.get(0).getHeader().getBlockId());
        assertEquals("dn-2:50052", received.get(0).getHeader().getDownstream(0).getAddr());
        assertEquals(4, received.get(2).getChunk().getOffset());
        assertEquals("aa", received.get(3).getTrailer().getSha256());
    }

    @Test
    void noEnviaMasDeLoQueElNodoAcepta() throws Exception {
        manualFlow = true;
        CountingSource source = new CountingSource(2000, 64 * 1024);
        var result = gateway.escribir(new BlockTarget("blk_1", 1, 1, "dn-1", "proceso", List.of()), source, "tok");
        assertTrue(headerSeen.await(5, TimeUnit.SECONDS));
        Thread.sleep(150);
        assertTrue(source.delivered.get() < 100, "entregó " + source.delivered.get() + " fragmentos sin demanda");
        writeStream.request(Integer.MAX_VALUE);
        result.get(10, TimeUnit.SECONDS);
        assertEquals(2000, source.delivered.get());
    }

    @Test
    void leeLosNodosFallidosDelTrailer() {
        onWriteEnd = responses -> {
            Metadata trailers = new Metadata();
            trailers.put(FAILED, "dn-2,dn-3");
            responses.onError(Status.ABORTED.asRuntimeException(trailers));
        };
        var error = assertThrows(ExecutionException.class,
                () -> gateway.escribir(TARGET, new CountingSource(1, 4), "tok").get(5, TimeUnit.SECONDS));
        var failure = assertInstanceOf(ReplicaWriteException.class, error.getCause());
        assertTrue(failure.esRecuperable());
        assertEquals(List.of("dn-2", "dn-3"), failure.obtenerNodosFallidos());
    }

    @Test
    void sinTrailerElFallidoEsElPrimeroDeLaCadena() {
        onWriteEnd = responses -> responses.onError(Status.UNAVAILABLE.asRuntimeException());
        var error = assertThrows(ExecutionException.class,
                () -> gateway.escribir(TARGET, new CountingSource(1, 4), "tok").get(5, TimeUnit.SECONDS));
        var failure = assertInstanceOf(ReplicaWriteException.class, error.getCause());
        assertEquals(List.of("dn-1"), failure.obtenerNodosFallidos());
    }

    @Test
    void permisosDenegadosNoSonRecuperables() {
        onWriteEnd = responses -> responses.onError(Status.PERMISSION_DENIED.asRuntimeException());
        var error = assertThrows(ExecutionException.class,
                () -> gateway.escribir(TARGET, new CountingSource(1, 4), "tok").get(5, TimeUnit.SECONDS));
        assertEquals(false, ((ReplicaWriteException) error.getCause()).esRecuperable());
    }

    @Test
    void cancelaSiElNodoNoConfirma() {
        onWriteEnd = responses -> { };
        var error = assertThrows(ExecutionException.class,
                () -> gateway.escribir(TARGET, new CountingSource(1, 4), "tok").get(5, TimeUnit.SECONDS));
        assertTrue(((ReplicaWriteException) error.getCause()).esRecuperable());
    }

    @Test
    void leeFragmentosVerificados() {
        onRead = responses -> {
            responses.onNext(crearFragmento(0, new byte[] {1, 2}));
            responses.onNext(crearFragmento(2, new byte[] {3}));
            responses.onCompleted();
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        gateway.leer(REPLICA, "blk_1", 1, new ByteRange(0, 3), "tok",
                (offset, data) -> Channels.newChannel(output).write(data));
        assertArrayEquals(new byte[] {1, 2, 3}, output.toByteArray());
    }

    @Test
    void rechazaCrcIncorrecto() {
        onRead = responses -> {
            responses.onNext(crearFragmento(0, new byte[] {1}).toBuilder().setCrc32C(7).build());
            responses.onCompleted();
        };
        assertThrows(CorruptReplicaException.class,
                () -> gateway.leer(REPLICA, "blk_1", 1, new ByteRange(0, 1), "tok", (offset, data) -> { }));
    }

    @Test
    void rechazaOffsetInesperado() {
        onRead = responses -> {
            responses.onNext(crearFragmento(5, new byte[] {1}));
            responses.onCompleted();
        };
        assertThrows(CorruptReplicaException.class,
                () -> gateway.leer(REPLICA, "blk_1", 1, new ByteRange(0, 1), "tok", (offset, data) -> { }));
    }

    @Test
    void rechazaLecturaIncompleta() {
        onRead = responses -> {
            responses.onNext(crearFragmento(0, new byte[] {1}));
            responses.onCompleted();
        };
        assertThrows(CorruptReplicaException.class,
                () -> gateway.leer(REPLICA, "blk_1", 1, new ByteRange(0, 2), "tok", (offset, data) -> { }));
    }

    @Test
    void nodoCaidoEsFalloDeReplica() {
        onRead = responses -> responses.onError(Status.UNAVAILABLE.asRuntimeException());
        assertThrows(ReplicaReadException.class,
                () -> gateway.leer(REPLICA, "blk_1", 1, new ByteRange(0, 1), "tok", (offset, data) -> { }));
    }

    @Test
    void perdidaDeDatosReportadaEsCorrupcion() {
        onRead = responses -> responses.onError(Status.DATA_LOSS.asRuntimeException());
        assertThrows(CorruptReplicaException.class,
                () -> gateway.leer(REPLICA, "blk_1", 1, new ByteRange(0, 1), "tok", (offset, data) -> { }));
    }

    @Test
    void cancelaUnaLecturaSinAvance() {
        assertThrows(ReplicaReadException.class,
                () -> gateway.leer(REPLICA, "blk_1", 1, new ByteRange(0, 1), "tok", (offset, data) -> { }));
    }

    @Test
    void unFalloLocalNoSeConfundeConFalloDeReplica() {
        onRead = responses -> {
            responses.onNext(crearFragmento(0, new byte[] {1}));
            responses.onCompleted();
        };
        assertThrows(java.io.UncheckedIOException.class, () -> gateway.leer(REPLICA, "blk_1", 1,
                new ByteRange(0, 1), "tok", (offset, data) -> {
                    throw new IOException("disco lleno");
                }));
    }
}
