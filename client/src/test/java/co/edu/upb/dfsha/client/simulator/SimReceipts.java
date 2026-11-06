package co.edu.upb.dfsha.client.simulator;

import java.nio.charset.StandardCharsets;

/** Contenido firmado de un recibo, según el contrato del README raíz. */
final class SimReceipts {

    private SimReceipts() {
    }

    static byte[] crearContenido(String blockId, long gen, long length, String sha256, String nodeId) {
        return ("dfsha-receipt-v1|" + blockId + "|" + gen + "|" + length + "|" + sha256 + "|" + nodeId)
                .getBytes(StandardCharsets.UTF_8);
    }
}
