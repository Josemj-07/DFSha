package co.edu.upb.dfsha.node.adapter.storage;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.DataIntegrityException;
import co.edu.upb.dfsha.node.port.BlockWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Escribe un bloque en su archivo .part y lo publica como .blk solo si longitud y SHA-256 cuadran.
 * No es seguro entre hilos: cada escritura pertenece a un único stream gRPC, que entrega los
 * mensajes en orden.
 */
final class FileSystemBlockWriter implements BlockWriter {

    private enum Estado { ABIERTO, CONFIRMADO, ABORTADO }

    private final BlockKey key;
    private final long longitudEsperada;
    private final BlockPaths paths;
    private final MetaFileCodec codec;
    private final FileChannel canal;
    private final MessageDigest sha256;
    private long escritos;
    private boolean metaEscrita;
    private Estado estado = Estado.ABIERTO;

    FileSystemBlockWriter(BlockKey key, long longitudEsperada, BlockPaths paths, MetaFileCodec codec,
                          FileChannel canal) {
        this.key = key;
        this.longitudEsperada = longitudEsperada;
        this.paths = paths;
        this.codec = codec;
        this.canal = canal;
        this.sha256 = crearDigest();
    }

    @Override
    public void escribir(ByteBuffer data) {
        exigirAbierto();
        if (escritos + data.remaining() > longitudEsperada) {
            throw new DataIntegrityException("el bloque supera la longitud declarada", escritos);
        }
        sha256.update(data.duplicate());
        try {
            while (data.hasRemaining()) {
                escritos += canal.write(data);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * El orden importa: el nombre .blk es la marca de bloque completo, así que aparece al final,
     * cuando los datos y el .meta ya están en disco. Si el nodo cae antes, solo queda un .part.
     */
    @Override
    public BlockMeta confirmar(String expectedSha256) {
        exigirAbierto();
        try {
            verificarLongitud();
            BlockMeta meta = new BlockMeta(key, escritos, verificarHash(expectedSha256));
            escribirMeta(meta);
            canal.force(true);
            canal.close();
            Files.move(paths.obtenerParcial(key), paths.obtenerCompleto(key), StandardCopyOption.ATOMIC_MOVE);
            DirectorySync.sincronizar(paths.obtenerDirectorio(key));
            estado = Estado.CONFIRMADO;
            return meta;
        } catch (IOException e) {
            abortar();
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            abortar();
            throw e;
        }
    }

    @Override
    public void abortar() {
        if (estado != Estado.ABIERTO) {
            return;
        }
        estado = Estado.ABORTADO;
        try {
            canal.close();
            Files.deleteIfExists(paths.obtenerParcial(key));
            if (metaEscrita) {
                Files.deleteIfExists(paths.obtenerMeta(key));
            }
        } catch (IOException e) {
            // Un .part que no se pudo borrar lo elimina la limpieza periódica; no se pierde nada.
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        abortar();
    }

    private void verificarLongitud() {
        if (escritos != longitudEsperada) {
            throw new DataIntegrityException(
                    "longitud recibida " + escritos + " distinta de la declarada " + longitudEsperada, escritos);
        }
    }

    private String verificarHash(String esperado) {
        String calculado = HexFormat.of().formatHex(sha256.digest());
        byte[] esperadoBytes = esperado == null ? new byte[0] : esperado.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(calculado.getBytes(StandardCharsets.US_ASCII), esperadoBytes)) {
            throw new DataIntegrityException("SHA-256 del bloque no coincide", escritos);
        }
        return calculado;
    }

    private void escribirMeta(BlockMeta meta) throws IOException {
        Path archivo = paths.obtenerMeta(key);
        metaEscrita = true;
        try (var salida = FileChannel.open(archivo, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            salida.write(ByteBuffer.wrap(codec.escribir(meta).getBytes(StandardCharsets.UTF_8)));
            salida.force(true);
        }
    }

    private void exigirAbierto() {
        if (estado != Estado.ABIERTO) {
            throw new IllegalStateException("la escritura del bloque ya terminó");
        }
    }

    private static MessageDigest crearDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("la JVM no soporta SHA-256", e);
        }
    }
}
