package co.edu.upb.dfsha.client.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockBufferTest {

    @TempDir
    Path dir;

    @Test
    void guardaConPermisosPrivadosYLimpiaAlCerrar() throws IOException {
        BlockBuffer buffer = new BlockBuffer(dir, "h_1");
        buffer.crear(0);
        buffer.escribir(0, 0, ByteBuffer.wrap("hola".getBytes(StandardCharsets.UTF_8)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(buffer.obtenerRuta(0))));
        assertEquals(4, buffer.obtenerLongitud(0));
        ByteBuffer read = ByteBuffer.allocate(10);
        assertEquals(3, buffer.leer(0, 1, read));
        buffer.close();
        assertFalse(Files.exists(dir.resolve("h_1")));
    }

    @Test
    void rechazaUnHandleQueIntenteSalirDelDirectorio() {
        assertThrows(InvalidOperationException.class, () -> new BlockBuffer(dir, "../fuera"));
        assertFalse(Files.exists(dir.resolve("../fuera")));
    }

    @Test
    void descartaUnBloque() {
        BlockBuffer buffer = new BlockBuffer(dir, "h_2");
        buffer.crear(3);
        assertTrue(buffer.contiene(3));
        buffer.descartar(3);
        assertFalse(buffer.contiene(3));
    }
}
