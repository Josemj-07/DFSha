package co.edu.upb.dfsha.node.adapter.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Lee las claves en el formato PEM que producen los scripts de server/pki:
 * tls.key es EC P-256 en PKCS#8 y blocktoken.pub es Ed25519 en X.509 (SubjectPublicKeyInfo).
 */
public final class PemKeys {

    private PemKeys() {
    }

    /** Clave pública con la que el servidor firma los block tokens. */
    public static PublicKey cargarPublicaEd25519(Path archivo) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(decodificar(archivo)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("clave pública Ed25519 inválida: " + archivo.getFileName(), e);
        }
    }

    /** Clave privada TLS del nodo; con ella se firman los recibos. */
    public static PrivateKey cargarPrivadaEc(Path archivo) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(decodificar(archivo)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("clave privada EC inválida: " + archivo.getFileName(), e);
        }
    }

    public static X509Certificate cargarCertificado(Path archivo) {
        try (InputStream entrada = Files.newInputStream(archivo)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(entrada);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("certificado inválido: " + archivo.getFileName(), e);
        }
    }

    private static byte[] decodificar(Path archivo) {
        try {
            String pem = Files.readString(archivo).replaceAll("-----[A-Z ]+-----|\\s", "");
            return Base64.getDecoder().decode(pem);
        } catch (IOException e) {
            throw new IllegalStateException("no se pudo leer " + archivo.getFileName(), e);
        }
    }
}
