package co.edu.upb.dfsha.node.domain;

import java.util.Objects;

/**
 * Prueba firmada de que este nodo guardó el bloque completo.
 * El servidor la verifica con el certificado del nodo antes de publicar el archivo.
 */
public record Receipt(String nodeId, BlockMeta meta, byte[] signature) {

    public Receipt {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(signature, "signature");
        signature = signature.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    /** Texto exacto que se firma; debe coincidir byte a byte con el que verifica el servidor. */
    public static String construirContenidoFirmado(String nodeId, BlockMeta meta) {
        return "dfsha-receipt-v1|" + meta.key().blockId() + "|" + meta.key().gen() + "|"
                + meta.length() + "|" + meta.sha256() + "|" + nodeId;
    }
}
