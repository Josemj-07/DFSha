package co.edu.upb.dfsha.client.data;

/** Recibo firmado por un nodo tras guardar un bloque. */
public record Receipt(String nodeId, String blockId, long gen, long length, String sha256, byte[] signature) {
}
