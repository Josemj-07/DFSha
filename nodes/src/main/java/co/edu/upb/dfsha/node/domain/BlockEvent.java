package co.edu.upb.dfsha.node.domain;

/**
 * Aviso al servidor sobre un bloque de este nodo (recibido, borrado o corrupto).
 * eventId permite al servidor descartar duplicados: el outbox puede entregar un evento dos veces.
 */
public record BlockEvent(String eventId, String nodeId, String blockId, long gen, long length, String sha256) {

    public static BlockEvent de(String eventId, String nodeId, BlockMeta meta) {
        return new BlockEvent(eventId, nodeId, meta.key().blockId(), meta.key().gen(), meta.length(), meta.sha256());
    }
}
