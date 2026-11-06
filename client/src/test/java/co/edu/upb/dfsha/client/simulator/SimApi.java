package co.edu.upb.dfsha.client.simulator;

import static co.edu.upb.dfsha.client.simulator.SimMetadata.Block;
import static co.edu.upb.dfsha.client.simulator.SimMetadata.Entry;
import static co.edu.upb.dfsha.client.simulator.SimMetadata.Handle;
import static co.edu.upb.dfsha.client.simulator.SimMetadata.Lock;
import static co.edu.upb.dfsha.client.simulator.SimMetadata.Problem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operaciones REST del simulador, ya sin HTTP: reciben JSON como mapas y devuelven mapas. */
final class SimApi {

    private final SimMetadata state;

    SimApi(SimMetadata state) {
        this.state = state;
    }

    private Entry buscar(String path) {
        Entry entry = state.entries.get(path);
        if (entry == null) {
            throw new Problem(404, "NOT_FOUND", path);
        }
        return entry;
    }

    synchronized Map<String, Object> listar(String path, String cursor, int pageSize) {
        if (!buscar(path).dir) {
            throw new Problem(409, "NOT_A_DIRECTORY", path);
        }
        String prefix = path.equals("/") ? "/" : path + "/";
        List<Map<String, Object>> all = new ArrayList<>();
        state.entries.forEach((key, entry) -> {
            if (key.startsWith(prefix) && key.length() > prefix.length()
                    && key.indexOf('/', prefix.length()) < 0) {
                all.add(describir(key, entry));
            }
        });
        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int to = Math.min(all.size(), from + pageSize);
        Map<String, Object> page = new HashMap<>();
        page.put("entries", all.subList(from, to));
        page.put("nextCursor", to < all.size() ? Integer.toString(to) : null);
        return page;
    }

    synchronized Map<String, Object> atributos(String path) {
        return describir(path, buscar(path));
    }

    private Map<String, Object> describir(String path, Entry entry) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", path.substring(path.lastIndexOf('/') + 1));
        info.put("path", path);
        info.put("type", entry.dir ? "DIR" : "FILE");
        info.put("owner", entry.owner);
        info.put("mode", "rwxr-x---");
        info.put("size", entry.size);
        info.put("version", entry.version);
        info.put("mtime", "2026-11-06T12:00:00Z");
        return info;
    }

    synchronized void crearDirectorio(String path, boolean parents, String owner) {
        if (state.entries.containsKey(path)) {
            if (parents) {
                return;
            }
            throw new Problem(409, "ALREADY_EXISTS", path);
        }
        String parent = obtenerPadre(path);
        if (!state.entries.containsKey(parent)) {
            if (!parents) {
                throw new Problem(404, "NOT_FOUND", parent);
            }
            crearDirectorio(parent, true, owner);
        }
        Entry entry = new Entry();
        entry.dir = true;
        entry.owner = owner;
        state.entries.put(path, entry);
    }

    synchronized void borrarDirectorio(String path, boolean recursive) {
        buscar(path);
        String prefix = path + "/";
        List<String> children = state.entries.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        if (!children.isEmpty() && !recursive) {
            throw new Problem(409, "DIR_NOT_EMPTY", path);
        }
        children.forEach(state.entries::remove);
        state.entries.remove(path);
    }

    synchronized void borrarArchivo(String path) {
        buscar(path);
        state.entries.remove(path);
    }

    synchronized void mover(String from, String to) {
        if (state.entries.containsKey(to)) {
            throw new Problem(409, "ALREADY_EXISTS", to);
        }
        state.entries.put(to, state.entries.remove(requerir(from)));
    }

    synchronized void copiar(String from, String to) {
        Entry source = buscar(from);
        Entry copy = new Entry();
        copy.owner = source.owner;
        copy.size = source.size;
        copy.version = 1;
        copy.sha256 = source.sha256;
        copy.blocks.putAll(source.blocks);
        state.entries.put(to, copy);
    }

    private String requerir(String path) {
        buscar(path);
        return path;
    }

    private String obtenerPadre(String path) {
        int split = path.lastIndexOf('/');
        return split <= 0 ? "/" : path.substring(0, split);
    }

    synchronized Map<String, Object> abrir(Map<String, Object> request, String owner) {
        String path = (String) request.get("path");
        String mode = (String) request.get("mode");
        Entry entry = state.entries.get(path);
        if (entry != null && Boolean.TRUE.equals(request.get("exclusive"))) {
            throw new Problem(409, "ALREADY_EXISTS", path);
        }
        if (entry == null) {
            if (!Boolean.TRUE.equals(request.get("create"))) {
                throw new Problem(404, "NOT_FOUND", path);
            }
            entry = new Entry();
            entry.owner = owner;
            state.entries.put(path, entry);
        }
        Handle handle = new Handle();
        handle.id = state.nuevoId("h_");
        handle.path = path;
        handle.mode = mode;
        handle.baseVersion = entry.version;
        handle.baseBlocks = new java.util.TreeMap<>(entry.blocks);
        if (mode.equals("w") || mode.equals("a")) {
            comprobarLibre(path);
            handle.lockId = tomarExclusivo(path, handle);
        }
        state.handles.put(handle.id, handle);
        return construirApertura(handle, entry);
    }

    private void comprobarLibre(String path) {
        if (state.locks.values().stream().anyMatch(lock -> lock.path.equals(path))) {
            throw new Problem(409, "LOCK_CONFLICT", path);
        }
    }

    private String tomarExclusivo(String path, Handle handle) {
        Lock lock = new Lock();
        lock.id = state.nuevoId("l_");
        lock.path = path;
        lock.mode = "EXCLUSIVE";
        lock.token = state.nuevoFencing();
        state.locks.put(lock.id, lock);
        handle.fencingToken = lock.token;
        return lock.id;
    }

    private Map<String, Object> construirApertura(Handle handle, Entry entry) {
        boolean write = handle.mode.equals("w");
        List<Block> blocks = write ? List.of() : new ArrayList<>(entry.blocks.values());
        Map<String, Object> result = new HashMap<>();
        result.put("handle", handle.id);
        result.put("baseVersion", entry.version);
        result.put("blockSize", state.blockSize);
        result.put("size", write ? 0 : entry.size);
        result.put("fileSha256", write ? null : entry.sha256);
        Map<String, Object> lease = new HashMap<>();
        lease.put("fencingToken", handle.fencingToken);
        lease.put("ttlMs", 30000);
        result.put("lease", lease);
        result.put("readToken", write ? null : crearTokenLectura(entry));
        result.put("blocks", blocks.stream().map(this::describirBloque).toList());
        return result;
    }

    private String crearTokenLectura(Entry entry) {
        return SimTokens.crear("r", entry.blocks.values().stream().map(b -> b.blockId + ":" + b.gen).toList());
    }

    private Map<String, Object> describirBloque(Block block) {
        return Map.of("idx", block.idx, "blockId", block.blockId, "gen", block.gen, "len", block.len,
                "sha256", block.sha256, "replicas", block.replicas.stream().map(node -> Map.of(
                        "nodeId", node.obtenerId(), "addr", node.obtenerDireccionCliente(),
                        "az", node.obtenerZona())).toList());
    }

    private Handle obtenerHandle(String id) {
        Handle handle = state.handles.get(id);
        if (handle == null) {
            throw new Problem(410, "HANDLE_EXPIRED", id);
        }
        return handle;
    }

    synchronized Map<String, Object> asignar(String id, int firstIdx, int count) {
        Handle handle = obtenerHandle(id);
        List<Block> blocks = new ArrayList<>();
        for (int idx = firstIdx; idx < firstIdx + count; idx++) {
            Block block = state.crearBloque(idx, null, 1, state.elegirCadena(Set.of()));
            handle.pending.put(idx, block);
            blocks.add(block);
        }
        return construirAsignacion(blocks);
    }

    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> recuperar(String id, Map<String, Object> request) {
        Handle handle = obtenerHandle(id);
        int idx = (int) request.get("idx");
        Block previous = handle.pending.get(idx);
        Set<String> failed = new HashSet<>((List<String>) request.get("failedNodes"));
        Block block = state.crearBloque(idx, previous.blockId, previous.gen + 1, state.elegirCadena(failed));
        handle.pending.put(idx, block);
        return construirAsignacion(List.of(block));
    }

    private Map<String, Object> construirAsignacion(List<Block> blocks) {
        String token = SimTokens.crear("w", blocks.stream().map(b -> b.blockId + ":" + b.gen).toList());
        return Map.of("writeToken", token, "blocks", blocks.stream().map(block -> Map.of(
                "idx", block.idx, "blockId", block.blockId, "gen", block.gen,
                "pipeline", block.replicas.stream().map(node -> Map.of("nodeId", node.obtenerId(),
                        "clientAddr", node.obtenerDireccionCliente(),
                        "replicationAddr", node.obtenerDireccionReplicacion())).toList())).toList());
    }

    synchronized Map<String, Object> confirmar(String id, Map<String, Object> request) {
        return Map.of("version", state.confirmar(obtenerHandle(id), request));
    }

    synchronized Map<String, Object> mantener(String id) {
        Handle handle = obtenerHandle(id);
        Map<String, Object> result = new HashMap<>();
        result.put("ttlMs", 30000);
        Entry entry = state.entries.get(handle.path);
        result.put("readToken", handle.mode.equals("w") || entry == null ? null : crearTokenLectura(entry));
        return result;
    }

    synchronized void cerrar(String id) {
        Handle handle = obtenerHandle(id);
        state.handles.remove(id);
        if (handle.lockId != null) {
            state.locks.remove(handle.lockId);
        }
    }

    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> bloquear(Map<String, Object> request) {
        String path = (String) request.get("path");
        String mode = (String) request.get("mode");
        Map<String, Object> range = (Map<String, Object>) request.get("range");
        Integer first = range == null ? null : (Integer) range.get("first");
        Integer last = range == null ? null : (Integer) range.get("last");
        for (Lock lock : state.locks.values()) {
            if (lock.path.equals(path) && seSolapan(lock, first, last)
                    && (lock.mode.equals("EXCLUSIVE") || mode.equals("EXCLUSIVE"))) {
                throw new Problem(409, "LOCK_CONFLICT", path);
            }
        }
        Lock lock = new Lock();
        lock.id = state.nuevoId("l_");
        lock.path = path;
        lock.mode = mode;
        lock.first = first;
        lock.last = last;
        lock.token = state.nuevoFencing();
        state.locks.put(lock.id, lock);
        return Map.of("lockId", lock.id, "fencingToken", lock.token, "expiresAt", "2026-11-06T12:00:30Z");
    }

    private boolean seSolapan(Lock lock, Integer first, Integer last) {
        return lock.first == null || first == null || (lock.first <= last && first <= lock.last);
    }

    synchronized void renovarBloqueo(String id) {
        if (!state.locks.containsKey(id)) {
            throw new Problem(404, "NOT_FOUND", id);
        }
    }

    synchronized void desbloquear(String id) {
        state.locks.remove(id);
    }

    synchronized void reportar(Map<String, Object> report) {
        state.reports.add(report);
    }

    synchronized List<Map<String, Object>> listarNodos() {
        return state.nodes.stream().map(node -> Map.<String, Object>of("nodeId", node.obtenerId(),
                "addr", node.obtenerDireccionCliente(), "az", node.obtenerZona(), "status", "ALIVE",
                "capacityBytes", 1L << 40, "usedBytes", 0L)).toList();
    }
}
