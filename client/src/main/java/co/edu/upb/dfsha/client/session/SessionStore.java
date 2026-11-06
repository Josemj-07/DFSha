package co.edu.upb.dfsha.client.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Guarda la sesión en disco con permisos {@code 600} y escritura atómica. */
public final class SessionStore {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");

    private final Path file;
    private final Consumer<String> warnings;
    private final ObjectMapper json =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SessionStore(Path homeDir, Consumer<String> warnings) {
        this.file = homeDir.resolve("session.json");
        this.warnings = warnings;
    }

    public Optional<Session> cargar() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            corregirPermisos();
            return Optional.of(json.readValue(file.toFile(), Session.class));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la sesión en " + file, e);
        }
    }

    public void guardar(Session session) {
        try {
            Path dir = file.getParent();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_DIR));
            }
            Path temp = Files.createTempFile(dir, "session", ".tmp",
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            json.writeValue(temp.toFile(), session);
            mover(temp);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar la sesión en " + file, e);
        }
    }

    public void borrar() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar la sesión en " + file, e);
        }
    }

    private void mover(Path temp) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void corregirPermisos() throws IOException {
        if (!OWNER_ONLY.equals(Files.getPosixFilePermissions(file))) {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
            warnings.accept("Aviso: se corrigieron los permisos de " + file);
        }
    }
}
