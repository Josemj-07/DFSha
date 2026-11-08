package co.edu.upb.dfsha.node.adapter.crypto;

import co.edu.upb.dfsha.node.config.TestPki;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateHostCheckTest {

    @TempDir
    Path dir;

    @Test
    void aceptaUnHostQueEstaEnElSan() {
        var identidad = new TestPki(dir, "CA").emitir("dn-1", "dn-1", "localhost");

        assertDoesNotThrow(() -> CertificateHostCheck.verificar(PemKeys.cargarCertificado(identidad.certFile()), "dn-1"));
    }

    @Test
    void rechazaUnHostQueNoEstaEnElSanConUnMensajeClaro() {
        var identidad = new TestPki(dir, "CA").emitir("dn-1", "dn-1");

        var error = assertThrows(IllegalStateException.class,
                () -> CertificateHostCheck.verificar(PemKeys.cargarCertificado(identidad.certFile()), "10.0.1.21"));
        assertTrue(error.getMessage().contains("DFS_ADVERTISED_HOST=10.0.1.21"));
    }

    @Test
    void rechazaUnCertificadoSinSan() throws URISyntaxException {
        Path sinSan = Path.of(getClass().getResource("/crypto/dn-prueba-cert.pem").toURI());

        assertThrows(IllegalStateException.class,
                () -> CertificateHostCheck.verificar(PemKeys.cargarCertificado(sinSan), "dn-prueba"));
    }
}
