package co.edu.upb.dfsha.node.adapter.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PemKeysTest {

    @TempDir
    Path dir;

    @Test
    void leeLaClavePublicaDelServidor() throws IOException {
        KeyPair par = TestKeys.generarEd25519();
        Path archivo = TestKeys.escribirPem(dir.resolve("blocktoken.pub"), "PUBLIC KEY", par.getPublic());

        assertArrayEquals(par.getPublic().getEncoded(), PemKeys.cargarPublicaEd25519(archivo).getEncoded());
    }

    @Test
    void leeLaClavePrivadaDelNodo() throws IOException {
        KeyPair par = TestKeys.generarEcP256();
        Path archivo = TestKeys.escribirPem(dir.resolve("tls.key"), "PRIVATE KEY", par.getPrivate());

        assertArrayEquals(par.getPrivate().getEncoded(), PemKeys.cargarPrivadaEc(archivo).getEncoded());
    }

    @Test
    void leeUnCertificado() throws URISyntaxException {
        Path archivo = Path.of(getClass().getResource("/crypto/dn-prueba-cert.pem").toURI());

        var certificado = PemKeys.cargarCertificado(archivo);

        assertTrue(certificado.getSubjectX500Principal().getName().contains("CN=dn-prueba"));
        assertEquals("EC", certificado.getPublicKey().getAlgorithm());
    }

    @Test
    void fallaConUnArchivoQueNoEsClave() throws IOException {
        Path archivo = Files.writeString(dir.resolve("basura.pub"), "no es una clave");

        assertThrows(IllegalStateException.class, () -> PemKeys.cargarPublicaEd25519(archivo));
    }

    @Test
    void fallaSiFaltaElArchivo() {
        assertThrows(IllegalStateException.class, () -> PemKeys.cargarPrivadaEc(dir.resolve("no-existe.key")));
    }
}
