package co.edu.upb.dfsha.node.adapter.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Claves generadas al vuelo: así ninguna clave privada de prueba entra al repositorio. */
final class TestKeys {

    private TestKeys() {
    }

    static KeyPair generarEd25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair generarEcP256() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("EC");
            generador.initialize(new ECGenParameterSpec("secp256r1"));
            return generador.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mismo formato que produce openssl: PKCS#8 para privadas y X.509 para públicas. */
    static Path escribirPem(Path archivo, String tipo, Key clave) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(clave.getEncoded());
        Files.writeString(archivo, "-----BEGIN " + tipo + "-----\n" + base64 + "\n-----END " + tipo + "-----\n");
        return archivo;
    }
}
