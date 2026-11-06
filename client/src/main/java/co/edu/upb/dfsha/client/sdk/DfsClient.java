package co.edu.upb.dfsha.client.sdk;

import co.edu.upb.dfsha.client.config.PathResolver;
import co.edu.upb.dfsha.client.metadata.AdminGateway;
import co.edu.upb.dfsha.client.metadata.DirectoryPage;
import co.edu.upb.dfsha.client.metadata.LockRequest;
import co.edu.upb.dfsha.client.metadata.NamespaceGateway;
import co.edu.upb.dfsha.client.metadata.OpenRequest;
import co.edu.upb.dfsha.client.metadata.OpenResult;
import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import co.edu.upb.dfsha.client.sdk.model.AuditEntry;
import co.edu.upb.dfsha.client.sdk.model.BlockRange;
import co.edu.upb.dfsha.client.sdk.model.FileInfo;
import co.edu.upb.dfsha.client.sdk.model.LockHandle;
import co.edu.upb.dfsha.client.sdk.model.LockMode;
import co.edu.upb.dfsha.client.sdk.model.NodeInfo;
import co.edu.upb.dfsha.client.sdk.model.OpenMode;
import co.edu.upb.dfsha.client.sdk.model.ProgressListener;
import co.edu.upb.dfsha.client.session.Session;
import co.edu.upb.dfsha.client.session.SessionStore;
import co.edu.upb.dfsha.client.session.TokenManager;
import co.edu.upb.dfsha.client.transfer.BlockBuffer;
import co.edu.upb.dfsha.client.transfer.Downloader;
import co.edu.upb.dfsha.client.transfer.Uploader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Fachada del SDK. Las rutas relativas se resuelven contra el directorio actual de la sesión. */
public final class DfsClient implements AutoCloseable {

    /** Colaboradores de la fachada. */
    record Parts(TokenManager tokens, SessionStore sessions, NamespaceGateway namespace, AdminGateway admin,
                 Uploader uploader, Downloader downloader, RemoteFile.Services files, Path tmpDir,
                 List<AutoCloseable> resources) {
    }

    private final Parts parts;

    DfsClient(Parts parts) {
        this.parts = parts;
    }

    public void iniciarSesion(String username, char[] password) {
        parts.tokens().iniciarSesion(username, password);
    }

    public void cerrarSesion() {
        parts.tokens().cerrarSesion();
    }

    public String obtenerUsuario() {
        return parts.tokens().obtenerSesion().username();
    }

    public String obtenerDirectorioActual() {
        return parts.tokens().obtenerSesion().cwd();
    }

    public void cambiarDirectorio(String path) {
        String target = resolver(path);
        if (!parts.namespace().obtenerAtributos(target).esDirectorio()) {
            throw new InvalidOperationException("NOT_A_DIRECTORY", target + " no es un directorio");
        }
        Session session = parts.tokens().obtenerSesion();
        parts.sessions().guardar(session.conDirectorio(target));
    }

    public List<FileInfo> listar(String path) {
        String target = resolver(path);
        List<FileInfo> entries = new ArrayList<>();
        String cursor = null;
        do {
            DirectoryPage page = parts.namespace().listar(target, cursor);
            entries.addAll(page.entries());
            cursor = page.nextCursor();
        } while (cursor != null);
        return entries;
    }

    public FileInfo obtenerAtributos(String path) {
        return parts.namespace().obtenerAtributos(resolver(path));
    }

    public void crearDirectorio(String path, boolean parents) {
        parts.namespace().crearDirectorio(resolver(path), parents);
    }

    public void borrarDirectorio(String path, boolean recursive) {
        parts.namespace().borrarDirectorio(resolver(path), recursive);
    }

    public void borrar(String path) {
        parts.namespace().borrar(resolver(path));
    }

    public void mover(String from, String to) {
        parts.namespace().mover(resolver(from), resolver(to));
    }

    public void copiar(String from, String to) {
        parts.namespace().copiar(resolver(from), resolver(to));
    }

    public long subir(Path local, String remote, boolean exclusive, ProgressListener progress) {
        String target = remote.endsWith("/") ? remote + local.getFileName() : remote;
        return parts.uploader().subir(local, resolver(target), exclusive, progress);
    }

    public void descargar(String remote, Path local, ProgressListener progress) {
        String source = resolver(remote);
        Path target = Files.isDirectory(local) ? local.resolve(PathResolver.obtenerNombre(source)) : local;
        parts.downloader().descargar(source, target.toAbsolutePath(), progress);
    }

    public DfsFile abrir(String path, OpenMode mode, boolean exclusive) {
        String target = resolver(path);
        boolean create = mode == OpenMode.WRITE || mode == OpenMode.APPEND;
        OpenResult opened = parts.files().files()
                .abrir(new OpenRequest(target, mode.obtenerValor(), create, exclusive));
        BlockBuffer buffer = new BlockBuffer(parts.tmpDir(), opened.handle());
        return new RemoteFile(target, mode, opened, buffer, parts.files());
    }

    public void copiarContenido(String path, OutputStream output) {
        try (DfsFile file = abrir(path, OpenMode.READ, false)) {
            RemoteContent.copiar(file, 0, Long.MAX_VALUE, output);
        }
    }

    public void copiarInicio(String path, Long lines, Long bytes, OutputStream output) {
        try (DfsFile file = abrir(path, OpenMode.READ, false)) {
            long limit = bytes != null ? bytes : RemoteContent.medirPrimerasLineas(file, lines);
            RemoteContent.copiar(file, 0, limit, output);
        }
    }

    public void copiarFinal(String path, Long lines, Long bytes, OutputStream output) {
        try (DfsFile file = abrir(path, OpenMode.READ, false)) {
            long start = bytes != null
                    ? Math.max(0, file.obtenerTamanio() - bytes)
                    : RemoteContent.buscarInicioUltimasLineas(file, lines);
            RemoteContent.copiar(file, start, Long.MAX_VALUE, output);
        }
    }

    public LockHandle bloquear(String path, LockMode mode, BlockRange range, Duration ttl, Duration wait) {
        LockHandle lock = parts.files().locks()
                .bloquear(new LockRequest(resolver(path), mode.name(), range, ttl.toMillis(), wait.toMillis()));
        parts.files().keeper().registrarBloqueo(lock.lockId());
        return lock;
    }

    public void desbloquear(String lockId) {
        parts.files().keeper().quitarBloqueo(lockId);
        parts.files().locks().desbloquear(lockId);
    }

    public void crearUsuario(String username, char[] password, String role) {
        parts.admin().crearUsuario(username, password, role);
    }

    public void asignarCuota(String username, long quotaBytes) {
        parts.admin().asignarCuota(username, quotaBytes);
    }

    public List<NodeInfo> listarNodos() {
        return parts.admin().listarNodos();
    }

    public List<AuditEntry> listarAuditoria() {
        return parts.admin().listarAuditoria();
    }

    public String resolver(String path) {
        return PathResolver.resolver(obtenerDirectorioActual(), path);
    }

    @Override
    public void close() {
        for (AutoCloseable resource : parts.resources()) {
            try {
                resource.close();
            } catch (Exception e) {
                // Al terminar el proceso no hay nada útil que hacer con este fallo.
            }
        }
    }
}
