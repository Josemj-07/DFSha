package co.edu.upb.dfsha.server.blocks.application;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class ReceiptVerifier {
  public boolean verificar(
      PublicKey key,
      String block,
      long gen,
      long length,
      String sha,
      String node,
      String signature) {
    try {
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(key);
      verifier.update(
          ("dfsha-receipt-v1|" + block + "|" + gen + "|" + length + "|" + sha + "|" + node)
              .getBytes(StandardCharsets.UTF_8));
      return verifier.verify(Base64.getDecoder().decode(signature));
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      return false;
    }
  }
}
