package co.edu.upb.dfsha.client.sdk;

import co.edu.upb.dfsha.client.sdk.model.BlockRange;
import co.edu.upb.dfsha.client.sdk.model.LockHandle;
import co.edu.upb.dfsha.client.sdk.model.LockMode;
import java.nio.ByteBuffer;
import java.time.Duration;

/** Archivo abierto. Las escrituras son locales hasta {@link #volcar()} o {@link #close()}. */
public interface DfsFile extends AutoCloseable {

    /** @return bytes leídos, o -1 si {@code offset} está en el final del archivo o después */
    int leer(long offset, ByteBuffer destination);

    /** En modo {@code a} escribe siempre al final e ignora {@code offset}. */
    void escribir(long offset, ByteBuffer source);

    /** @return la versión confirmada */
    long volcar();

    LockHandle bloquear(LockMode mode, BlockRange range, Duration ttl, Duration wait);

    void desbloquear(String lockId);

    long obtenerTamanio();

    String obtenerRuta();

    boolean tieneCambiosPendientes();

    @Override
    void close();
}
