package co.edu.upb.dfsha.client.data;

/** Origen de los bytes de un bloque, entregados por fragmentos. */
public interface BlockSource extends AutoCloseable {

    /** Fragmento listo para enviar, con su suma de verificación. */
    record Fragment(long offset, byte[] data, int crc32c) {
    }

    boolean tieneSiguiente();

    Fragment siguiente();

    /** SHA-256 del bloque. Solo es válido cuando ya se entregaron todos los fragmentos. */
    String obtenerSha256();

    @Override
    void close();
}
