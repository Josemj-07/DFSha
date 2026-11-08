package co.edu.upb.dfsha.node.adapter.storage;

import co.edu.upb.dfsha.node.domain.BlockKey;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Única clase que construye rutas de bloques. Concentrarlo aquí permite comprobar en un solo
 * sitio que ninguna ruta se sale del directorio de datos.
 *
 * <pre>
 * &lt;dataDir&gt;/blocks/&lt;últimos 2 caracteres del id&gt;/&lt;blockId&gt;_&lt;gen&gt;.{part,blk,meta,corrupt}
 * </pre>
 * Se reparte en subdirectorios para no acumular miles de archivos en uno solo.
 */
public final class BlockPaths {

    static final String PARCIAL = ".part";
    static final String COMPLETO = ".blk";
    static final String META = ".meta";
    static final String CORRUPTO = ".corrupt";

    private final Path raiz;

    public BlockPaths(Path dataDir) {
        this.raiz = dataDir.resolve("blocks").toAbsolutePath().normalize();
    }

    public Path obtenerRaiz() {
        return raiz;
    }

    public Path obtenerDirectorio(BlockKey key) {
        String id = key.blockId();
        return verificarDentroDeRaiz(raiz.resolve(id.substring(id.length() - 2)));
    }

    public Path obtenerParcial(BlockKey key) {
        return resolverArchivo(key, PARCIAL);
    }

    public Path obtenerCompleto(BlockKey key) {
        return resolverArchivo(key, COMPLETO);
    }

    public Path obtenerMeta(BlockKey key) {
        return resolverArchivo(key, META);
    }

    public Path obtenerCorrupto(BlockKey key) {
        return resolverArchivo(key, CORRUPTO);
    }

    /** Recupera la clave a partir del nombre de un archivo del almacén; vacío si no es de bloque. */
    public Optional<BlockKey> interpretarNombre(Path archivo, String extension) {
        String nombre = archivo.getFileName().toString();
        if (!nombre.endsWith(extension)) {
            return Optional.empty();
        }
        String base = nombre.substring(0, nombre.length() - extension.length());
        int separador = base.lastIndexOf('_');
        if (separador <= 0) {
            return Optional.empty();
        }
        try {
            long gen = Long.parseLong(base.substring(separador + 1));
            return Optional.of(new BlockKey(base.substring(0, separador), gen));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Path resolverArchivo(BlockKey key, String extension) {
        Path directorio = obtenerDirectorio(key);
        return verificarDentroDeRaiz(directorio.resolve(key.blockId() + "_" + key.gen() + extension));
    }

    // BlockKey ya valida el id; esta comprobación es una segunda barrera por si esa validación cambia.
    private Path verificarDentroDeRaiz(Path ruta) {
        Path normalizada = ruta.toAbsolutePath().normalize();
        if (!normalizada.startsWith(raiz)) {
            throw new IllegalArgumentException("ruta fuera del almacén de bloques");
        }
        return normalizada;
    }
}
