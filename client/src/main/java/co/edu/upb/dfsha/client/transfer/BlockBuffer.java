package co.edu.upb.dfsha.client.transfer;

import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

/** Copia local de los bloques tocados de un archivo abierto. Vive en disco, no en memoria. */
public final class BlockBuffer implements AutoCloseable {

    private final Path dir;

    public BlockBuffer(Path tmpRoot, String handle) {
        // El handle viene de la red y forma parte de una ruta local.
        if (!handle.matches("[A-Za-z0-9_-]+")) {
            throw new InvalidOperationException("INVALID_HANDLE", "Handle inválido recibido del servidor");
        }
        this.dir = tmpRoot.resolve(handle);
        try {
            Files.createDirectories(tmpRoot);
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el buffer local en " + dir, e);
        }
    }

    public boolean contiene(int idx) {
        return Files.exists(obtenerRuta(idx));
    }

    public Path obtenerRuta(int idx) {
        return dir.resolve(idx + ".buf");
    }

    public void crear(int idx) {
        try {
            Files.createFile(obtenerRuta(idx),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el buffer del bloque " + idx, e);
        }
    }

    public void descartar(int idx) {
        borrar(obtenerRuta(idx));
    }

    public void escribir(int idx, long position, ByteBuffer data) {
        try (FileChannel channel = FileChannel.open(obtenerRuta(idx), StandardOpenOption.WRITE)) {
            long current = position;
            while (data.hasRemaining()) {
                current += channel.write(data, current);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el buffer del bloque " + idx, e);
        }
    }

    /** Lee hasta llenar {@code destination} o llegar al final del bloque. */
    public int leer(int idx, long position, ByteBuffer destination) {
        try (FileChannel channel = FileChannel.open(obtenerRuta(idx), StandardOpenOption.READ)) {
            int total = 0;
            while (destination.hasRemaining()) {
                int read = channel.read(destination, position + total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return total;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el buffer del bloque " + idx, e);
        }
    }

    public long obtenerLongitud(int idx) {
        try {
            return Files.size(obtenerRuta(idx));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo medir el buffer del bloque " + idx, e);
        }
    }

    @Override
    public void close() {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(this::borrar);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo limpiar " + dir, e);
        }
    }

    private void borrar(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar " + file, e);
        }
    }
}
