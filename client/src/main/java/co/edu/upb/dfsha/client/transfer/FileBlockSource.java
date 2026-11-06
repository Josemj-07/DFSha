package co.edu.upb.dfsha.client.transfer;

import co.edu.upb.dfsha.client.data.BlockSource;
import co.edu.upb.dfsha.client.integrity.Checksums;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/**
 * Lee un bloque de un archivo local por fragmentos, calculando CRC32C y SHA-256 en la misma pasada.
 * Cada instancia abre su propio canal: la posición no se comparte entre hilos.
 */
final class FileBlockSource implements BlockSource {

    private final FileChannel channel;
    private final long start;
    private final long length;
    private final int chunkSize;
    private final MessageDigest digest = Checksums.crearSha256();
    private long sent;
    private String sha256;

    FileBlockSource(Path file, long start, long length, int chunkSize) {
        this.start = start;
        this.length = length;
        this.chunkSize = chunkSize;
        try {
            this.channel = FileChannel.open(file, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo abrir " + file, e);
        }
    }

    @Override
    public boolean tieneSiguiente() {
        return sent < length;
    }

    @Override
    public Fragment siguiente() {
        int size = (int) Math.min(chunkSize, length - sent);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        leerCompleto(buffer);
        buffer.flip();
        int crc = Checksums.calcularCrc32c(buffer);
        digest.update(buffer.duplicate());
        Fragment fragment = new Fragment(sent, buffer.array(), crc);
        sent += size;
        return fragment;
    }

    private void leerCompleto(ByteBuffer buffer) {
        try {
            long position = start + sent;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read < 0) {
                    throw new IOException("El archivo local se acortó durante la lectura");
                }
                position += read;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String obtenerSha256() {
        if (sha256 == null) {
            sha256 = Checksums.convertirAHex(digest.digest());
        }
        return sha256;
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
