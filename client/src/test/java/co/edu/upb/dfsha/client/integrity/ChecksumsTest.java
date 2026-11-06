package co.edu.upb.dfsha.client.integrity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ChecksumsTest {

    @Test
    void calculaElValorEstandarDeCrc32c() {
        ByteBuffer data = ByteBuffer.wrap("123456789".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0xE3069283, Checksums.calcularCrc32c(data));
        assertEquals(0, data.position(), "no debe consumir el buffer");
    }

    @Test
    void comparaHashesSinImportarMayusculas() {
        assertTrue(Checksums.sonIguales("ABCD", "abcd"));
        assertFalse(Checksums.sonIguales("abcd", "abce"));
        assertFalse(Checksums.sonIguales(null, "abcd"));
    }
}
