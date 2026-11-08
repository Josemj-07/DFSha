package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.Downstream;
import co.edu.upb.dfsha.proto.v1.ReplicationServiceGrpc;
import co.edu.upb.dfsha.proto.v1.WriteHeader;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import co.edu.upb.dfsha.proto.v1.WriteTrailer;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.ManagedChannel;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escritura en cadena con nodos reales conectados por gRPC: cliente → dn-1 → dn-2 → dn-3.
 * Usa bloques de varios MiB, mayores que la ventana de flujo, como pide nodes/README.md.
 */
class WriteChainTest {

    private static final BlockKey BLOQUE = new BlockKey("blk_01", 1);
    private static final String TOKEN = "w:blk_01:1";
    private static final int TAMANIO = 3 * 1024 * 1024 + 300_000;
    private static final int MIB = 1024 * 1024;

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private final List<AutoCloseable> recursos = new ArrayList<>();
    private TestNode dn1;
    private TestNode dn2;
    private TestNode dn3;
    private byte[] datos;

    @BeforeEach
    void arrancar() throws IOException {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        dn1 = nodo("dn-1");
        dn2 = nodo("dn-2");
        dn3 = nodo("dn-3");
        datos = new byte[TAMANIO];
        new Random(11).nextBytes(datos);
    }

    @AfterEach
    void detener() throws Exception {
        for (AutoCloseable recurso : recursos) {
            recurso.close();
        }
        scheduler.shutdownNow();
    }

    @Test
    void unSoloNodoGuardaElBloqueYDevuelveSuRecibo() throws Exception {
        WriteResponse respuesta = escribir(dn1, TOKEN, mensajesValidos()).get(10, TimeUnit.SECONDS);

        assertEquals(1, respuesta.getReceiptsCount());
        assertReciboValido(respuesta.getReceipts(0), dn1);
        assertEquals(1, dn1.contarArchivos(".blk"));
        assertEquals(List.of("block.received.dn-1"), dn1.eventos);
    }

    @Test
    void laCadenaDeTresNodosDejaTresCopiasYTresRecibos() throws Exception {
        WriteResponse respuesta = escribir(dn1, TOKEN, mensajesValidos(dn2, dn3)).get(10, TimeUnit.SECONDS);

        assertEquals(3, respuesta.getReceiptsCount());
        assertReciboValido(respuesta.getReceipts(0), dn1);
        assertReciboValido(respuesta.getReceipts(1), dn2);
        assertReciboValido(respuesta.getReceipts(2), dn3);
        for (TestNode nodo : List.of(dn1, dn2, dn3)) {
            assertArrayEquals(datos, leerLocal(nodo));
            assertEquals(0, nodo.contarArchivos(".part"));
        }
    }

    @Test
    void siFallaElSegundoNodoRespondeAbortedYNoDejaPart() throws Exception {
        var caido = Downstream.newBuilder().setNodeId("dn-2").setAddr("no-existe").build();

        StatusRuntimeException error = fallo(escribir(dn1, TOKEN, mensajes(List.of(caido), datos, sha(datos), 0)));

        assertEquals(Status.Code.ABORTED, error.getStatus().getCode());
        assertEquals("dn-2", nodosFallidos(error));
        esperarSinArchivos(dn1, ".part");
        assertEquals(0, dn1.contarArchivos(".blk"));
    }

    @Test
    void siFallaElTerceroLaCulpaLlegaHastaElCliente() throws Exception {
        var caido = Downstream.newBuilder().setNodeId("dn-3").setAddr("no-existe").build();

        StatusRuntimeException error = fallo(escribir(dn1, TOKEN,
                mensajes(List.of(dn2.comoDestino(), caido), datos, sha(datos), 0)));

        assertEquals(Status.Code.ABORTED, error.getStatus().getCode());
        assertEquals("dn-3", nodosFallidos(error));
        esperarSinArchivos(dn1, ".part");
        esperarSinArchivos(dn2, ".part");
    }

    @Test
    void siElSiguienteNodoNoConfirmaSeLeDeclaraFallido() throws Exception {
        String colgado = servidorQueNuncaResponde();
        var destino = Downstream.newBuilder().setNodeId("dn-lento").setAddr(colgado).build();

        StatusRuntimeException error = fallo(escribir(dn1, TOKEN, mensajes(List.of(destino), datos, sha(datos), 0)));

        assertEquals(Status.Code.ABORTED, error.getStatus().getCode());
        assertEquals("dn-lento", nodosFallidos(error));
    }

    @Test
    void unCrcIncorrectoRespondeDataLossYNingunNodoGuardaNada() throws Exception {
        StatusRuntimeException error = fallo(escribir(dn1, TOKEN,
                mensajes(List.of(dn2.comoDestino()), datos, sha(datos), 2)));

        assertEquals(Status.Code.DATA_LOSS, error.getStatus().getCode());
        for (TestNode nodo : List.of(dn1, dn2)) {
            esperarSinArchivos(nodo, ".part");
            assertEquals(0, nodo.contarArchivos(".blk"));
        }
    }

    @Test
    void unHashFinalIncorrectoRespondeDataLoss() throws Exception {
        String hashFalso = sha(new byte[] {1});

        StatusRuntimeException error = fallo(escribir(dn1, TOKEN, mensajes(List.of(), datos, hashFalso, 0)));

        assertEquals(Status.Code.DATA_LOSS, error.getStatus().getCode());
        assertEquals(0, dn1.contarArchivos(".blk"));
        esperarSinArchivos(dn1, ".part");
    }

    @Test
    void unTokenDeLecturaNoPuedeEscribir() {
        StatusRuntimeException error = fallo(escribir(dn1, "r:blk_01:1", mensajesValidos()));

        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
    }

    @Test
    void unBloqueYaGuardadoNoSeReescribe() throws Exception {
        escribir(dn1, TOKEN, mensajesValidos()).get(10, TimeUnit.SECONDS);

        StatusRuntimeException error = fallo(escribir(dn1, TOKEN, mensajesValidos()));

        assertEquals(Status.Code.ALREADY_EXISTS, error.getStatus().getCode());
    }

    @Test
    void unChunkAntesDelHeaderRespondeInvalidArgument() {
        List<WriteRequest> mensajes = new ArrayList<>(mensajesValidos());
        mensajes.remove(0);

        assertEquals(Status.Code.INVALID_ARGUMENT, fallo(escribir(dn1, TOKEN, mensajes)).getStatus().getCode());
    }

    @Test
    void terminarSinTrailerRespondeInvalidArgument() throws Exception {
        List<WriteRequest> mensajes = new ArrayList<>(mensajesValidos());
        mensajes.remove(mensajes.size() - 1);

        assertEquals(Status.Code.INVALID_ARGUMENT, fallo(escribir(dn1, TOKEN, mensajes)).getStatus().getCode());
        esperarSinArchivos(dn1, ".part");
    }

    private TestNode nodo(String id) throws IOException {
        var nodo = new TestNode(id, dir, scheduler, Duration.ofSeconds(2), Duration.ofMillis(800));
        recursos.add(nodo);
        return nodo;
    }

    private List<WriteRequest> mensajesValidos(TestNode... siguientes) {
        return mensajes(Arrays.stream(siguientes).map(TestNode::comoDestino).toList(), datos, sha(datos), 0);
    }

    /** @param chunkDaniado número de chunk (desde 1) con CRC alterado; 0 para ninguno */
    private static List<WriteRequest> mensajes(List<Downstream> cadena, byte[] contenido, String sha256,
                                               int chunkDaniado) {
        List<WriteRequest> mensajes = new ArrayList<>();
        mensajes.add(WriteRequest.newBuilder().setHeader(WriteHeader.newBuilder().setBlockId(BLOQUE.blockId())
                .setGen(BLOQUE.gen()).setLength(contenido.length).addAllDownstream(cadena)).build());
        int numero = 0;
        for (int offset = 0; offset < contenido.length; offset += MIB) {
            numero++;
            byte[] parte = Arrays.copyOfRange(contenido, offset, Math.min(contenido.length, offset + MIB));
            CRC32C crc = new CRC32C();
            crc.update(parte);
            int valor = (int) crc.getValue() + (numero == chunkDaniado ? 1 : 0);
            mensajes.add(WriteRequest.newBuilder().setChunk(Chunk.newBuilder().setOffset(offset)
                    .setData(ByteString.copyFrom(parte)).setCrc32C(valor)).build());
        }
        mensajes.add(WriteRequest.newBuilder().setTrailer(WriteTrailer.newBuilder().setSha256(sha256)).build());
        return mensajes;
    }

    private CompletableFuture<WriteResponse> escribir(TestNode destino, String token, List<WriteRequest> mensajes) {
        ManagedChannel canal = InProcessChannelBuilder.forName(destino.direccion).build();
        recursos.add(canal::shutdownNow);
        Metadata headers = new Metadata();
        headers.put(AuthInterceptor.AUTHORIZATION, "Bearer " + token);
        CompletableFuture<WriteResponse> resultado = new CompletableFuture<>();
        StreamObserver<WriteRequest> envio = BlockServiceGrpc.newStub(canal)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .escribirBloque(new StreamObserver<>() {
                    private WriteResponse respuesta;

                    @Override
                    public void onNext(WriteResponse value) {
                        respuesta = value;
                    }

                    @Override
                    public void onError(Throwable t) {
                        resultado.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        resultado.complete(respuesta);
                    }
                });
        mensajes.forEach(envio::onNext);
        envio.onCompleted();
        return resultado;
    }

    /** Un nodo que acepta la conexión pero nunca confirma la escritura. */
    private String servidorQueNuncaResponde() throws IOException {
        String nombre = "colgado-" + System.nanoTime();
        Server servidor = InProcessServerBuilder.forName(nombre).addService(
                new ReplicationServiceGrpc.ReplicationServiceImplBase() {
                    @Override
                    public StreamObserver<WriteRequest> replicarBloque(StreamObserver<WriteResponse> responses) {
                        return new StreamObserver<>() {
                            @Override
                            public void onNext(WriteRequest value) {
                            }

                            @Override
                            public void onError(Throwable t) {
                            }

                            @Override
                            public void onCompleted() {
                            }
                        };
                    }
                }).build().start();
        recursos.add(servidor::shutdownNow);
        return nombre;
    }

    private static StatusRuntimeException fallo(CompletableFuture<WriteResponse> resultado) {
        var error = assertThrows(ExecutionException.class, () -> resultado.get(10, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof StatusRuntimeException, "error inesperado: " + error.getCause());
        return (StatusRuntimeException) error.getCause();
    }

    private static String nodosFallidos(StatusRuntimeException error) {
        Metadata trailers = Status.trailersFromThrowable(error);
        return trailers == null ? null : trailers.get(WriteSession.FAILED_NODES);
    }

    // Las cancelaciones viajan entre nodos de forma asíncrona: se espera un momento a que limpien.
    private static void esperarSinArchivos(TestNode nodo, String extension) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (nodo.contarArchivos(extension) > 0 && System.nanoTime() < limite) {
            Thread.sleep(50);
        }
        assertEquals(0, nodo.contarArchivos(extension), "quedaron archivos " + extension + " en " + nodo.id);
    }

    private static byte[] leerLocal(TestNode nodo) throws IOException {
        try (var entrada = java.nio.channels.Channels.newInputStream(nodo.store.abrir(BLOQUE, 0))) {
            return entrada.readAllBytes();
        }
    }

    private static void assertReciboValido(co.edu.upb.dfsha.proto.v1.Receipt recibo, TestNode nodo)
            throws GeneralSecurityException {
        assertEquals(nodo.id, recibo.getNodeId());
        var meta = new BlockMeta(new BlockKey(recibo.getBlockId(), recibo.getGen()), recibo.getLength(),
                recibo.getSha256());
        assertTrue(verificar(nodo.claves.getPublic(),
                co.edu.upb.dfsha.node.domain.Receipt.construirContenidoFirmado(nodo.id, meta),
                recibo.getSignature().toByteArray()), "firma inválida del recibo de " + nodo.id);
    }

    private static boolean verificar(PublicKey clave, String contenido, byte[] firma) throws GeneralSecurityException {
        Signature verificador = Signature.getInstance("SHA256withECDSA");
        verificador.initVerify(clave);
        verificador.update(contenido.getBytes(StandardCharsets.UTF_8));
        return verificador.verify(firma);
    }

    private static String sha(byte[] contenido) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
