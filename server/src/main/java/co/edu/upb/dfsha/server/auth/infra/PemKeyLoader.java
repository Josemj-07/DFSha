package co.edu.upb.dfsha.server.auth.infra;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

public final class PemKeyLoader {
  private PemKeyLoader() {}

  private static byte[] decodificar(Path path) throws IOException {
    return Base64.getDecoder()
        .decode(Files.readString(path).replaceAll("-----[A-Z ]+-----|\\s", ""));
  }

  public static PrivateKey cargarPrivada(Path path) throws GeneralSecurityException, IOException {
    return KeyFactory.getInstance("Ed25519")
        .generatePrivate(new PKCS8EncodedKeySpec(decodificar(path)));
  }

  public static PublicKey cargarPublica(Path path) throws GeneralSecurityException, IOException {
    return KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(decodificar(path)));
  }
}
