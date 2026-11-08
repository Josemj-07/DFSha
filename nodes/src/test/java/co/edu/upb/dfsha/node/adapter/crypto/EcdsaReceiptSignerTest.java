package co.edu.upb.dfsha.node.adapter.crypto;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.Receipt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcdsaReceiptSignerTest {

    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final KeyPair claveNodo = TestKeys.generarEcP256();
    private final EcdsaReceiptSigner signer = new EcdsaReceiptSigner("dn-1", claveNodo.getPrivate());
    private final BlockMeta meta = new BlockMeta(new BlockKey("blk_01", 1), 134217728, SHA);

    @Test
    void elServidorPuedeVerificarElRecibo() {
        Receipt recibo = signer.firmar(meta);

        assertEquals("dn-1", recibo.nodeId());
        assertTrue(verificarComoElServidor(claveNodo.getPublic(), "blk_01", 1, 134217728, SHA, "dn-1",
                Base64.getEncoder().encodeToString(recibo.signature())));
    }

    @Test
    void unReciboNoSirveParaOtroBloque() {
        String firma = Base64.getEncoder().encodeToString(signer.firmar(meta).signature());

        assertFalse(verificarComoElServidor(claveNodo.getPublic(), "blk_02", 1, 134217728, SHA, "dn-1", firma));
        assertFalse(verificarComoElServidor(claveNodo.getPublic(), "blk_01", 2, 134217728, SHA, "dn-1", firma));
    }

    @Test
    void unReciboNoSirveParaOtroNodo() {
        String firma = Base64.getEncoder().encodeToString(signer.firmar(meta).signature());

        assertFalse(verificarComoElServidor(claveNodo.getPublic(), "blk_01", 1, 134217728, SHA, "dn-2", firma));
    }

    @Test
    void otroNodoNoPuedeFalsificarElRecibo() {
        var impostor = new EcdsaReceiptSigner("dn-1", TestKeys.generarEcP256().getPrivate());
        String firma = Base64.getEncoder().encodeToString(impostor.firmar(meta).signature());

        assertFalse(verificarComoElServidor(claveNodo.getPublic(), "blk_01", 1, 134217728, SHA, "dn-1", firma));
    }

    // Copia de ReceiptVerifier.verificar del servidor, para comprobar que ambos lados coinciden.
    private static boolean verificarComoElServidor(PublicKey key, String block, long gen, long length, String sha,
                                                   String node, String signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(("dfsha-receipt-v1|" + block + "|" + gen + "|" + length + "|" + sha + "|" + node)
                    .getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
