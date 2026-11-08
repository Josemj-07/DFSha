package co.edu.upb.dfsha.node.port;

import co.edu.upb.dfsha.node.domain.BlockMeta;

import java.nio.ByteBuffer;

/** Escritura en curso de un bloque. Hasta confirmar(), el bloque no existe para nadie más. */
public interface BlockWriter extends AutoCloseable {

    void escribir(ByteBuffer data);

    /** Verifica longitud y SHA-256 y publica el bloque de forma atómica. Lanza si no coincide. */
    BlockMeta confirmar(String expectedSha256);

    /** Descarta lo escrito. */
    void abortar();

    /** Si no se confirmó, aborta: así un error nunca deja un bloque a medias. */
    @Override
    void close();
}
