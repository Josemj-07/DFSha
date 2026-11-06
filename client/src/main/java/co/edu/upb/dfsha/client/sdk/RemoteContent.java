package co.edu.upb.dfsha.client.sdk;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/** Utilidades de lectura secuencial sobre un archivo abierto: cat, head y tail. */
final class RemoteContent {

    private static final int READ_SIZE = 1024 * 1024;
    private static final int SCAN_SIZE = 64 * 1024;
    private static final byte NEWLINE = '\n';

    private RemoteContent() {
    }

    static void copiar(DfsFile file, long start, long limit, OutputStream output) {
        ByteBuffer buffer = ByteBuffer.allocate(READ_SIZE);
        long position = start;
        long remaining = limit;
        while (remaining > 0) {
            buffer.clear().limit((int) Math.min(READ_SIZE, remaining));
            int read = file.leer(position, buffer);
            if (read < 0) {
                return;
            }
            escribir(output, buffer.array(), read);
            position += read;
            remaining -= read;
        }
    }

    /** Devuelve cuántos bytes ocupan las primeras {@code lines} líneas. */
    static long medirPrimerasLineas(DfsFile file, long lines) {
        ByteBuffer buffer = ByteBuffer.allocate(SCAN_SIZE);
        long position = 0;
        long found = 0;
        while (true) {
            buffer.clear();
            int read = file.leer(position, buffer);
            if (read < 0) {
                return position;
            }
            for (int i = 0; i < read; i++) {
                if (buffer.get(i) == NEWLINE && ++found == lines) {
                    return position + i + 1;
                }
            }
            position += read;
        }
    }

    /** Devuelve el offset donde empiezan las últimas {@code lines} líneas, leyendo hacia atrás. */
    static long buscarInicioUltimasLineas(DfsFile file, long lines) {
        long end = file.obtenerTamanio();
        long found = 0;
        long position = end;
        ByteBuffer buffer = ByteBuffer.allocate(SCAN_SIZE);
        while (position > 0) {
            long start = Math.max(0, position - SCAN_SIZE);
            buffer.clear().limit((int) (position - start));
            file.leer(start, buffer);
            for (int i = (int) (position - start) - 1; i >= 0; i--) {
                boolean finalNewline = start + i == end - 1;
                if (buffer.get(i) == NEWLINE && !finalNewline && ++found == lines) {
                    return start + i + 1;
                }
            }
            position = start;
        }
        return 0;
    }

    private static void escribir(OutputStream output, byte[] data, int length) {
        try {
            output.write(data, 0, length);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir la salida", e);
        }
    }
}
