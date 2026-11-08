package co.edu.upb.dfsha.node.adapter.storage;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Deja el almacén en un estado coherente tras una caída: ninguna escritura a medias sobrevive y
 * ningún bloque se ofrece sin metadatos que coincidan con su tamaño real.
 */
public class BlockStoreRecovery {

    private static final Logger log = LoggerFactory.getLogger(BlockStoreRecovery.class);

    private final FileSystemBlockStore store;
    private final BlockPaths paths;
    private final Clock clock;

    public BlockStoreRecovery(FileSystemBlockStore store, BlockPaths paths, Clock clock) {
        this.store = store;
        this.paths = paths;
        this.clock = clock;
    }

    /**
     * Se ejecuta al arrancar, antes de aceptar conexiones. En ese momento no hay escrituras en curso,
     * así que todo .part pertenece a una escritura interrumpida y se puede borrar.
     */
    public RecoveryReport recuperar() {
        int partes = 0;
        int validos = 0;
        int cuarentena = 0;
        int huerfanas = 0;
        for (Path archivo : store.listarArchivos()) {
            String nombre = archivo.getFileName().toString();
            if (nombre.endsWith(BlockPaths.PARCIAL)) {
                borrar(archivo);
                partes++;
            } else if (nombre.endsWith(BlockPaths.COMPLETO)) {
                boolean valido = revisarBloque(archivo);
                validos += valido ? 1 : 0;
                cuarentena += valido ? 0 : 1;
            } else if (nombre.endsWith(BlockPaths.META) && esHuerfana(archivo)) {
                borrar(archivo);
                huerfanas++;
            }
        }
        var reporte = new RecoveryReport(partes, validos, cuarentena, huerfanas);
        log.info("Almacén revisado: {}", reporte);
        return reporte;
    }

    /** Borra los .part sin actividad reciente: escrituras cuyo cliente desapareció sin cancelar. */
    public int limpiarPartesAbandonadas(Duration inactividad) {
        Instant limite = clock.instant().minus(inactividad);
        int borradas = 0;
        for (Path archivo : store.listarArchivos()) {
            if (archivo.getFileName().toString().endsWith(BlockPaths.PARCIAL) && esAnterior(archivo, limite)) {
                borrar(archivo);
                borradas++;
            }
        }
        return borradas;
    }

    private boolean revisarBloque(Path archivo) {
        Optional<BlockKey> key = paths.interpretarNombre(archivo, BlockPaths.COMPLETO);
        if (key.isEmpty()) {
            log.warn("Archivo con nombre inválido en el almacén: {}", archivo.getFileName());
            return false;
        }
        Optional<BlockMeta> meta = store.leerMeta(key.get());
        if (meta.isPresent() && meta.get().length() == tamanio(archivo)) {
            return true;
        }
        log.warn("Bloque {} sin metadatos coherentes: pasa a cuarentena", key.get().obtenerAlcance());
        store.ponerEnCuarentena(key.get());
        return false;
    }

    private boolean esHuerfana(Path meta) {
        return paths.interpretarNombre(meta, BlockPaths.META)
                .map(key -> !Files.exists(paths.obtenerCompleto(key)))
                .orElse(true);
    }

    private static boolean esAnterior(Path archivo, Instant limite) {
        try {
            return Files.getLastModifiedTime(archivo).toInstant().isBefore(limite);
        } catch (IOException e) {
            return false;
        }
    }

    private static long tamanio(Path archivo) {
        try {
            return Files.size(archivo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void borrar(Path archivo) {
        try {
            Files.deleteIfExists(archivo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
