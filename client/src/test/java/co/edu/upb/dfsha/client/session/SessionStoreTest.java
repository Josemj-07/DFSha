package co.edu.upb.dfsha.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();
    private final Session session = new Session("https://s:1", "jose", "a", "r", 99, "/home/jose");

    private SessionStore crear() {
        return new SessionStore(dir.resolve("dfs"), warnings::add);
    }

    @Test
    void guardaConPermisos600() throws IOException {
        crear().guardar(session);
        Path file = dir.resolve("dfs/session.json");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
    }

    @Test
    void recuperaLoGuardado() {
        crear().guardar(session);
        assertEquals(session, crear().cargar().orElseThrow());
    }

    @Test
    void corrigePermisosAbiertosYAvisa() throws IOException {
        crear().guardar(session);
        Path file = dir.resolve("dfs/session.json");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        crear().cargar();
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals(1, warnings.size());
    }

    @Test
    void noDejaTemporalesAlEscribir() throws IOException {
        crear().guardar(session);
        crear().guardar(session);
        try (var files = Files.list(dir.resolve("dfs"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void sinSesionDevuelveVacio() {
        assertTrue(crear().cargar().isEmpty());
        crear().guardar(session);
        crear().borrar();
        assertFalse(crear().cargar().isPresent());
    }
}
