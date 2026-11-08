package co.edu.upb.dfsha.node.port;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;

import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Optional;

/** Almacén de bloques inmutables. La implementación real guarda en disco. */
public interface BlockStore {

    /** Empieza a escribir un bloque nuevo. Lanza BlockAlreadyExistsException si ya está completo. */
    BlockWriter crear(BlockKey key, long length);

    Optional<BlockMeta> buscar(BlockKey key);

    /** Abre un bloque completo para leerlo desde un offset. Lanza BlockNotFoundException si no existe. */
    ReadableByteChannel abrir(BlockKey key, long offset);

    void borrar(BlockKey key);

    /** Aparta un bloque corrupto para que nadie lo vuelva a leer. */
    void ponerEnCuarentena(BlockKey key);

    List<BlockMeta> listar();

    long obtenerBytesDisponibles();

    long obtenerBytesCapacidad();
}
