package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.domain.BlockEvent;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.port.BlockWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrubberServiceTest {

    private static final BlockKey SANO = new BlockKey("blk_aa", 1);
    private static final BlockKey DANIADO = new BlockKey("blk_bb", 1);

    @TempDir
    Path dataDir;

    private BlockPaths paths;
    private FileSystemBlockStore store;
    private final RecordingPublisher publicador = new RecordingPublisher();
    private final AtomicLong bytesLimitados = new AtomicLong();
    private ScrubberService scrubber;

    @BeforeEach
    void preparar() throws NoSuchAlgorithmException {
        paths = new BlockPaths(dataDir);
        store = new FileSystemBlockStore(paths, new MetaFileCodec(), 0);
        guardar(SANO, 2 * 1024 * 1024 + 10);
        guardar(DANIADO, 1024 * 1024);
        scrubber = new ScrubberService("dn-1", store, publicador, bytesLimitados::addAndGet);
    }

    @Test
    void sinDaniosNoReportaNada() throws InterruptedException {
        assertEquals(new ScrubReport(2, 0), scrubber.revisarTodo());

        assertTrue(publicador.publicados.isEmpty());
    }

    @Test
    void detectaUnByteAlteradoYLoPoneEnCuarentena() throws IOException, InterruptedException {
        alterarUnByte(DANIADO, 500_000);

        ScrubReport reporte = scrubber.revisarTodo();

        assertEquals(new ScrubReport(2, 1), reporte);
        assertTrue(store.buscar(DANIADO).isEmpty());
        assertTrue(Files.exists(paths.obtenerCorrupto(DANIADO)));
        assertTrue(store.buscar(SANO).isPresent());
        var publicado = publicador.publicados.getFirst();
        assertEquals("block.corrupt.dn-1", publicado.routingKey());
        assertTrue(publicado.durable());
        assertEquals("blk_bb", ((BlockEvent) publicado.evento()).blockId());
    }

    @Test
    void detectaUnBloqueTruncado() throws IOException, InterruptedException {
        try (var archivo = new RandomAccessFile(paths.obtenerCompleto(DANIADO).toFile(), "rw")) {
            archivo.setLength(100);
        }

        assertEquals(1, scrubber.revisarTodo().corruptos());
    }

    @Test
    void unBloqueBorradoDuranteLaRevisionSeIgnora() throws InterruptedException {
        BlockMeta meta = store.buscar(SANO).orElseThrow();
        store.borrar(SANO);

        assertEquals(ScrubberService.Resultado.DESAPARECIDO, scrubber.revisar(meta));
        assertTrue(publicador.publicados.isEmpty());
    }

    @Test
    void leeElDiscoAVelocidadLimitada() throws InterruptedException {
        scrubber.revisarTodo();

        assertEquals(2 * 1024 * 1024 + 10 + 1024 * 1024, bytesLimitados.get());
    }

    @Test
    void noRevisaLosPartEnEscritura() throws InterruptedException {
        try (BlockWriter enCurso = store.crear(new BlockKey("blk_cc", 1), 10)) {
            enCurso.escribir(ByteBuffer.wrap(new byte[5]));

            assertEquals(2, scrubber.revisarTodo().revisados());
        }
        assertFalse(publicador.publicados.stream().anyMatch(p -> p.routingKey().startsWith("block.corrupt")));
    }

    private void alterarUnByte(BlockKey key, long posicion) throws IOException {
        try (var archivo = new RandomAccessFile(paths.obtenerCompleto(key).toFile(), "rw")) {
            archivo.seek(posicion);
            int original = archivo.read();
            archivo.seek(posicion);
            archivo.write(original ^ 0x01);
        }
    }

    private void guardar(BlockKey key, int tamanio) throws NoSuchAlgorithmException {
        byte[] datos = new byte[tamanio];
        new Random(key.blockId().hashCode()).nextBytes(datos);
        BlockWriter writer = store.crear(key, tamanio);
        writer.escribir(ByteBuffer.wrap(datos));
        writer.confirmar(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos)));
    }

}
