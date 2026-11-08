package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.application.ReadBlockService;
import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.InvalidTokenException;
import co.edu.upb.dfsha.node.domain.Op;
import co.edu.upb.dfsha.node.port.BlockWriter;
import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lectura de punta a punta: cliente gRPC, interceptor, servicio y almacén real en disco.
 * La escritura no se usa aquí (por eso su fábrica es null); la prueba WriteChainTest.
 */
class BlockGrpcServiceTest {

    private static final BlockKey BLOQUE = new BlockKey("blk_01", 1);
    // 3,5 MiB: obliga a enviar 4 chunks, el último incompleto.
    private static final int TAMANIO = 3 * 1024 * 1024 + 512 * 1024;

    @TempDir
    Path dataDir;

    private FileSystemBlockStore store;
    private Server servidor;
    private ManagedChannel canal;
    private byte[] datos;

    @BeforeEach
    void arrancar() throws IOException, NoSuchAlgorithmException {
        store = new FileSystemBlockStore(new BlockPaths(dataDir), new MetaFileCodec(), 0);
        datos = new byte[TAMANIO];
        new Random(7).nextBytes(datos);
        guardar(BLOQUE, datos);

        // Doble del verificador: el token es "<ops>:<bloque>", por ejemplo "r:blk_01:1".
        var auth = new AuthInterceptor(token -> {
            String[] partes = token.split(":", 2);
            if (partes.length != 2) {
                throw new InvalidTokenException("token inválido");
            }
            Set<Op> ops = partes[0].equals("r") ? Set.of(Op.READ) : Set.of(Op.WRITE);
            return new BlockGrant("jose", ops, Set.of(partes[1]));
        });
        String nombre = InProcessServerBuilder.generateName();
        servidor = InProcessServerBuilder.forName(nombre).directExecutor()
                .addService(ServerInterceptors.intercept(new BlockGrpcService(new ReadBlockService(store), null), auth))
                .build().start();
        canal = InProcessChannelBuilder.forName(nombre).directExecutor().build();
    }

    @AfterEach
    void detener() throws InterruptedException {
        canal.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        servidor.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void leeElBloqueCompletoEnChunksDe1MiB() {
        List<Chunk> chunks = leer("r:blk_01:1", 0, 0);

        assertEquals(4, chunks.size());
        assertEquals(BlockGrpcService.TAMANIO_CHUNK, chunks.get(0).getData().size());
        assertEquals(512 * 1024, chunks.get(3).getData().size());
        assertArrayEquals(datos, unir(chunks));
    }

    @Test
    void cadaChunkTraeSuOffsetYUnCrcCorrecto() {
        long esperado = 0;
        for (Chunk chunk : leer("r:blk_01:1", 0, 0)) {
            assertEquals(esperado, chunk.getOffset());
            CRC32C crc = new CRC32C();
            crc.update(chunk.getData().toByteArray());
            assertEquals((int) crc.getValue(), chunk.getCrc32C());
            esperado += chunk.getData().size();
        }
    }

    @Test
    void leeUnRangoDesdeLaMitad() {
        int offset = 1024 * 1024 + 100;
        List<Chunk> chunks = leer("r:blk_01:1", offset, 2000);

        assertEquals(offset, chunks.get(0).getOffset());
        assertArrayEquals(Arrays.copyOfRange(datos, offset, offset + 2000), unir(chunks));
    }

    @Test
    void unBloqueVacioCompletaSinChunks() throws NoSuchAlgorithmException {
        var vacio = new BlockKey("blk_00", 1);
        guardar(vacio, new byte[0]);

        assertEquals(0, leerBloque("r:blk_00:1", vacio, 0, 0).size());
    }

    @Test
    void unBloqueInexistenteRespondeNotFound() {
        var otro = new BlockKey("blk_99", 1);

        assertEquals(Status.Code.NOT_FOUND, codigoDeError(() -> leerBloque("r:blk_99:1", otro, 0, 0)));
    }

    @Test
    void otraGeneracionRespondeNotFound() {
        var gen2 = new BlockKey("blk_01", 2);

        assertEquals(Status.Code.NOT_FOUND, codigoDeError(() -> leerBloque("r:blk_01:2", gen2, 0, 0)));
    }

    @Test
    void unBloqueEnCuarentenaRespondeNotFound() {
        store.ponerEnCuarentena(BLOQUE);

        assertEquals(Status.Code.NOT_FOUND, codigoDeError(() -> leer("r:blk_01:1", 0, 0)));
    }

    @Test
    void unTokenDeOtroBloqueRespondePermissionDenied() {
        assertEquals(Status.Code.PERMISSION_DENIED, codigoDeError(() -> leer("r:blk_02:1", 0, 0)));
    }

    @Test
    void unTokenDeEscrituraNoPermiteLeer() {
        assertEquals(Status.Code.PERMISSION_DENIED, codigoDeError(() -> leer("w:blk_01:1", 0, 0)));
    }

    @Test
    void unRangoFueraDelBloqueRespondeInvalidArgument() {
        assertEquals(Status.Code.INVALID_ARGUMENT, codigoDeError(() -> leer("r:blk_01:1", TAMANIO + 1, 0)));
    }

    @Test
    void unBlockIdMaliciosoRespondeInvalidArgument() {
        ReadRequest request = ReadRequest.newBuilder().setBlockId("../../etc/passwd").setGen(1).build();

        assertEquals(Status.Code.INVALID_ARGUMENT, codigoDeError(() -> recolectar(stub("r:blk_01:1").leerBloque(request))));
    }

    private List<Chunk> leer(String token, long offset, long length) {
        return leerBloque(token, BLOQUE, offset, length);
    }

    private List<Chunk> leerBloque(String token, BlockKey key, long offset, long length) {
        ReadRequest request = ReadRequest.newBuilder().setBlockId(key.blockId()).setGen(key.gen())
                .setOffset(offset).setLength(length).build();
        return recolectar(stub(token).leerBloque(request));
    }

    private BlockServiceGrpc.BlockServiceBlockingStub stub(String token) {
        Metadata headers = new Metadata();
        headers.put(AuthInterceptor.AUTHORIZATION, "Bearer " + token);
        return BlockServiceGrpc.newBlockingStub(canal)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static List<Chunk> recolectar(Iterator<Chunk> respuesta) {
        List<Chunk> chunks = new ArrayList<>();
        respuesta.forEachRemaining(chunks::add);
        return chunks;
    }

    private static byte[] unir(List<Chunk> chunks) {
        var salida = new ByteArrayOutputStream();
        chunks.forEach(chunk -> salida.writeBytes(chunk.getData().toByteArray()));
        return salida.toByteArray();
    }

    private static Status.Code codigoDeError(Runnable llamada) {
        return assertThrows(StatusRuntimeException.class, llamada::run).getStatus().getCode();
    }

    private void guardar(BlockKey key, byte[] contenido) throws NoSuchAlgorithmException {
        BlockWriter writer = store.crear(key, contenido.length);
        writer.escribir(ByteBuffer.wrap(contenido));
        writer.confirmar(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido)));
    }
}
