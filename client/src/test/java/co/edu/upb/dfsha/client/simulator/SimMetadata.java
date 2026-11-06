package co.edu.upb.dfsha.client.simulator;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Estado y reglas del servidor simulado. El commit sigue el orden de {@code server/README.md}. */
final class SimMetadata {

    static final class Block {
        int idx;
        String blockId;
        long gen;
        long len;
        String sha256;
        List<SimNode> replicas = new ArrayList<>();
    }

    static final class Entry {
        boolean dir;
        String owner;
        long version;
        long size;
        String sha256;
        TreeMap<Integer, Block> blocks = new TreeMap<>();
    }

    static final class Handle {
        String id;
        String path;
        String mode;
        long baseVersion;
        TreeMap<Integer, Block> baseBlocks = new TreeMap<>();
        String fencingToken;
        Map<Integer, Block> pending = new HashMap<>();
        Long committedVersion;
        String lockId;
    }

    static final class Lock {
        String id;
        String path;
        String mode;
        Integer first;
        Integer last;
        String token;
    }

    /** Error de negocio con su estado HTTP y código del contrato. */
    static final class Problem extends RuntimeException {
        final int status;
        final String code;

        Problem(int status, String code, String detail) {
            super(detail);
            this.status = status;
            this.code = code;
        }
    }

    final Map<String, Entry> entries = new TreeMap<>();
    final Map<String, Handle> handles = new HashMap<>();
    final Map<String, Lock> locks = new LinkedHashMap<>();
    final List<SimNode> nodes;
    final long blockSize;
    final List<Map<String, Object>> reports = new ArrayList<>();
    private long fencing = 1000;
    private long nextId;
    private int rotation;

    SimMetadata(List<SimNode> nodes, long blockSize) {
        this.nodes = nodes;
        this.blockSize = blockSize;
        Entry root = new Entry();
        root.dir = true;
        entries.put("/", root);
    }

    synchronized String nuevoFencing() {
        return Long.toString(++fencing);
    }

    synchronized String nuevoId(String prefix) {
        return prefix + (++nextId);
    }

    synchronized List<SimNode> elegirCadena(Set<String> excluded) {
        List<SimNode> alive = new ArrayList<>(nodes.stream()
                .filter(node -> !excluded.contains(node.obtenerId())).toList());
        if (alive.size() < 2) {
            throw new Problem(503, "INSUFFICIENT_NODES", "sin nodos");
        }
        int start = rotation++ % alive.size();
        return List.of(alive.get(start), alive.get((start + 1) % alive.size()));
    }

    Block crearBloque(int idx, String blockId, long gen, List<SimNode> chain) {
        Block block = new Block();
        block.idx = idx;
        block.blockId = blockId == null ? "blk_" + UUID.randomUUID() : blockId;
        block.gen = gen;
        block.replicas = chain;
        return block;
    }

    /** Verificación de una publicación. Devuelve la versión creada. */
    @SuppressWarnings("unchecked")
    synchronized long confirmar(Handle handle, Map<String, Object> request) {
        Entry entry = entries.get(handle.path);
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) request.get("blocks");
        if (handle.committedVersion != null && blocks.isEmpty() && "w".equals(handle.mode)) {
            return handle.committedVersion;
        }
        verificarCerco(handle, (String) request.get("fencingToken"));
        TreeMap<Integer, Block> finalMap = "w".equals(handle.mode) ? new TreeMap<>() : new TreeMap<>(entry.blocks);
        for (Map<String, Object> item : blocks) {
            int idx = (int) item.get("idx");
            comprobarCas(handle, entry, idx);
            finalMap.put(idx, verificarBloque(handle, item));
        }
        long size = "rw".equals(handle.mode)
                ? finalMap.values().stream().mapToLong(b -> b.len).sum()
                : ((Number) request.get("size")).longValue();
        verificarMapa(finalMap, size, (String) request.get("fileSha256"));
        entry.blocks = finalMap;
        entry.size = size;
        entry.sha256 = calcularHash(finalMap);
        entry.version++;
        handle.committedVersion = entry.version;
        handle.baseVersion = entry.version;
        handle.baseBlocks = new TreeMap<>(finalMap);
        handle.pending.clear();
        return entry.version;
    }

    private void verificarCerco(Handle handle, String token) {
        boolean valid = token != null && (token.equals(handle.fencingToken)
                || locks.values().stream().anyMatch(lock -> lock.path.equals(handle.path) && token.equals(lock.token)));
        if (!valid) {
            throw new Problem(409, "STALE_FENCING_TOKEN", "token vencido");
        }
    }

    private void comprobarCas(Handle handle, Entry entry, int idx) {
        Block base = handle.baseBlocks.get(idx);
        Block current = entry.blocks.get(idx);
        boolean same = base == current || (base != null && current != null && base.blockId.equals(current.blockId)
                && base.gen == current.gen);
        if (!same && !"w".equals(handle.mode)) {
            throw new Problem(409, "VERSION_CONFLICT", "el bloque " + idx + " cambió");
        }
    }

    @SuppressWarnings("unchecked")
    private Block verificarBloque(Handle handle, Map<String, Object> item) {
        int idx = (int) item.get("idx");
        Block block = handle.pending.get(idx);
        String blockId = (String) item.get("blockId");
        long gen = ((Number) item.get("gen")).longValue();
        if (block == null || !block.blockId.equals(blockId) || block.gen != gen) {
            throw new Problem(422, "BLOCK_NOT_DURABLE", "bloque desconocido " + idx);
        }
        block.len = ((Number) item.get("len")).longValue();
        block.sha256 = (String) item.get("sha256");
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) item.get("receipts");
        Set<String> valid = new HashSet<>();
        for (Map<String, Object> receipt : receipts) {
            String nodeId = (String) receipt.get("node");
            if (verificarRecibo(block, nodeId, (String) receipt.get("sig"))) {
                valid.add(nodeId);
            }
        }
        if (valid.size() < 2) {
            throw new Problem(422, "BLOCK_NOT_DURABLE", "faltan recibos del bloque " + idx);
        }
        return block;
    }

    private boolean verificarRecibo(Block block, String nodeId, String signature) {
        SimNode node = nodes.stream().filter(n -> n.obtenerId().equals(nodeId)).findFirst().orElse(null);
        if (node == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(node.obtenerClavePublica());
            verifier.update(SimReceipts.crearContenido(block.blockId, block.gen, block.len, block.sha256, nodeId));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private void verificarMapa(TreeMap<Integer, Block> finalMap, long size, String fileSha256) {
        int expected = 0;
        long total = 0;
        for (var item : finalMap.entrySet()) {
            if (item.getKey() != expected++) {
                throw new Problem(422, "BLOCK_NOT_DURABLE", "hueco en el índice " + (expected - 1));
            }
            total += item.getValue().len;
        }
        if (total != size) {
            throw new Problem(422, "BLOCK_NOT_DURABLE", "tamaño incorrecto: " + total + " de " + size);
        }
        if (fileSha256 != null && !fileSha256.equals(calcularHash(finalMap))) {
            throw new Problem(422, "BLOCK_NOT_DURABLE", "fileSha256 incorrecto");
        }
    }

    static String calcularHash(TreeMap<Integer, Block> blocks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            blocks.values().forEach(block -> digest.update(HexFormat.of().parseHex(block.sha256)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
