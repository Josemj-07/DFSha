package co.edu.upb.dfsha.client.metadata;

/** Recibo de un nodo; la firma va en base64. */
public record BlockReceipt(String node, String sig) {
}
