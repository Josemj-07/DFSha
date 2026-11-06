package co.edu.upb.dfsha.client.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsFactoryTest {

    @TempDir
    Path dir;

    @Test
    void unArchivoInexistenteDaUnMensajeClaro() {
        var error = assertThrows(InvalidOperationException.class, () -> new TlsFactory(dir.resolve("ca.crt")));
        assertEquals("MISSING_CA", error.obtenerCodigo());
    }

    /** Docker crea un directorio si se monta una ruta que no existe en el host. */
    @Test
    void unDirectorioNoSeConfundeConElCertificado() throws IOException {
        Path folder = Files.createDirectory(dir.resolve("ca.crt"));
        var error = assertThrows(InvalidOperationException.class, () -> new TlsFactory(folder));
        assertEquals("MISSING_CA", error.obtenerCodigo());
    }

    @Test
    void unArchivoQueNoEsCertificadoSeRechaza() throws IOException {
        Path file = Files.writeString(dir.resolve("ca.crt"), "esto no es un certificado");
        var error = assertThrows(InvalidOperationException.class, () -> new TlsFactory(file).crearContextoHttp());
        assertEquals("INVALID_CA", error.obtenerCodigo());
    }
}
