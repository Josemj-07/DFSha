package co.edu.upb.dfsha.node.adapter.crypto;

import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.Receipt;
import co.edu.upb.dfsha.node.port.ReceiptSigner;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;

/**
 * Firma recibos con la clave privada TLS del nodo. El servidor los verifica con la clave pública
 * del certificado que el nodo envía al registrarse; así nadie puede afirmar que un nodo guardó
 * un bloque si no es cierto.
 */
public class EcdsaReceiptSigner implements ReceiptSigner {

    private final String nodeId;
    private final PrivateKey clavePrivada;

    public EcdsaReceiptSigner(String nodeId, PrivateKey clavePrivada) {
        this.nodeId = nodeId;
        this.clavePrivada = clavePrivada;
    }

    @Override
    public Receipt firmar(BlockMeta meta) {
        try {
            Signature firma = Signature.getInstance("SHA256withECDSA");
            firma.initSign(clavePrivada);
            firma.update(Receipt.construirContenidoFirmado(nodeId, meta).getBytes(StandardCharsets.UTF_8));
            return new Receipt(nodeId, meta, firma.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no se pudo firmar el recibo", e);
        }
    }
}
