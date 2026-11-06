package co.edu.upb.dfsha.client.sdk;

import co.edu.upb.dfsha.client.metadata.BlockInfo;
import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.OpenRequest;
import co.edu.upb.dfsha.client.metadata.OpenResult;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import co.edu.upb.dfsha.client.session.LocationCache;
import co.edu.upb.dfsha.client.transfer.NoReplicaAvailableException;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Mapa de bloques de un archivo abierto, con ubicaciones que caducan y se piden de nuevo al fallar. */
final class BlockLocator {

    private final String path;
    private final FileSessionGateway files;
    private final LocationCache cache;
    private final NavigableMap<Integer, BlockInfo> blocks = new TreeMap<>();
    private final Consumer<String> tokenUpdates;
    private long version;

    BlockLocator(String path, OpenResult opened, FileSessionGateway files, LocationCache cache,
                 Consumer<String> tokenUpdates) {
        this.path = path;
        this.files = files;
        this.cache = cache;
        this.tokenUpdates = tokenUpdates;
        this.version = opened.baseVersion();
        opened.blocks().forEach(block -> blocks.put(block.idx(), block));
        cache.guardar(path, version, opened.blocks());
    }

    BlockInfo obtener(int idx) {
        return blocks.get(idx);
    }

    List<BlockInfo> listar() {
        return List.copyOf(blocks.values());
    }

    long obtenerVersion() {
        return version;
    }

    void publicar(long newVersion, List<BlockInfo> published) {
        version = newVersion;
        published.forEach(block -> blocks.put(block.idx(), block));
        cache.guardar(path, version, listar());
    }

    /** Si ninguna réplica responde, olvida las ubicaciones y lo intenta una vez con las del servidor. */
    void leerConReubicacion(int idx, Consumer<BlockInfo> read) {
        try {
            read.accept(ubicar(idx));
        } catch (NoReplicaAvailableException e) {
            cache.invalidar(path, version);
            try {
                read.accept(ubicar(idx));
            } catch (NoReplicaAvailableException retry) {
                throw new UnavailableException("NO_REPLICAS", retry.getMessage(), retry);
            }
        }
    }

    private BlockInfo ubicar(int idx) {
        if (cache.obtener(path, version).isEmpty()) {
            refrescar();
        }
        return blocks.get(idx);
    }

    private void refrescar() {
        try {
            OpenResult fresh = files.abrir(new OpenRequest(path, "r", false, false));
            files.cerrar(fresh.handle());
            if (fresh.baseVersion() == version) {
                fresh.blocks().forEach(block -> blocks.put(block.idx(), block));
                tokenUpdates.accept(fresh.readToken());
            }
        } catch (DfsException e) {
            // Sin servidor se sigue con las ubicaciones conocidas: los nodos aún pueden responder.
        }
        cache.guardar(path, version, listar());
    }
}
