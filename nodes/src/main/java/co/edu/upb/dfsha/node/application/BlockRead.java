package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.BlockMeta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ReadableByteChannel;

/** Lectura autorizada y abierta de un rango de un bloque. Hay que cerrarla al terminar. */
public record BlockRead(BlockMeta meta, long offset, long length, ReadableByteChannel canal)
        implements AutoCloseable {

    @Override
    public void close() {
        try {
            canal.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
