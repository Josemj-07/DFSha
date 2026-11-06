package co.edu.upb.dfsha.client.session;

import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.KeepaliveResult;
import co.edu.upb.dfsha.client.metadata.LockGateway;
import co.edu.upb.dfsha.client.sdk.error.ConflictException;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Renueva leases de handles y locks en segundo plano, mientras el proceso siga vivo. */
public final class LeaseKeeper implements AutoCloseable {

    /** Recibe los eventos de un handle. */
    public interface HandleListener {
        void actualizarTokenLectura(String readToken);

        void notificarPerdida(DfsException cause);
    }

    private final FileSessionGateway files;
    private final LockGateway locks;
    private final Consumer<String> warnings;
    private final Map<String, HandleListener> handles = new ConcurrentHashMap<>();
    private final Set<String> lockIds = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;

    public LeaseKeeper(FileSessionGateway files, LockGateway locks, Duration interval, Consumer<String> warnings) {
        this.files = files;
        this.locks = locks;
        this.warnings = warnings;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "dfs-lease-keeper");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::renovarTodo, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public void registrarHandle(String handle, HandleListener listener) {
        handles.put(handle, listener);
    }

    public void quitarHandle(String handle) {
        handles.remove(handle);
    }

    public void registrarBloqueo(String lockId) {
        lockIds.add(lockId);
    }

    public void quitarBloqueo(String lockId) {
        lockIds.remove(lockId);
    }

    private void renovarTodo() {
        handles.forEach(this::renovarHandle);
        lockIds.forEach(this::renovarBloqueo);
    }

    private void renovarHandle(String handle, HandleListener listener) {
        try {
            KeepaliveResult result = files.mantenerActivo(handle);
            if (result.readToken() != null) {
                listener.actualizarTokenLectura(result.readToken());
            }
        } catch (ConflictException e) {
            handles.remove(handle);
            listener.notificarPerdida(e);
        } catch (DfsException e) {
            warnings.accept("Aviso: no se pudo renovar el archivo abierto: " + e.getMessage());
        }
    }

    private void renovarBloqueo(String lockId) {
        try {
            locks.renovarBloqueo(lockId);
        } catch (DfsException e) {
            warnings.accept("Aviso: no se pudo renovar el lock " + lockId + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
