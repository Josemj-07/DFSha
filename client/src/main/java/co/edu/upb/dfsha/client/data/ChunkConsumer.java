package co.edu.upb.dfsha.client.data;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Recibe los fragmentos ya verificados de una lectura. */
@FunctionalInterface
public interface ChunkConsumer {

    /** @param offset posición dentro del bloque */
    void aceptar(long offset, ByteBuffer data) throws IOException;
}
