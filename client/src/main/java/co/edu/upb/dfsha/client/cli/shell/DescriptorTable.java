package co.edu.upb.dfsha.client.cli.shell;

import co.edu.upb.dfsha.client.sdk.DfsFile;
import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Descriptores abiertos y locks de la sesión interactiva. */
public final class DescriptorTable {

    private final Map<Integer, DfsFile> files = new TreeMap<>();
    private final Set<String> freeLocks = ConcurrentHashMap.newKeySet();
    private final Map<String, DfsFile> fileLocks = new ConcurrentHashMap<>();
    private int nextDescriptor = 3;

    public synchronized int registrar(DfsFile file) {
        files.put(nextDescriptor, file);
        return nextDescriptor++;
    }

    public synchronized DfsFile obtener(int descriptor) {
        DfsFile file = files.get(descriptor);
        if (file == null) {
            throw new InvalidOperationException("BAD_DESCRIPTOR", "Descriptor inválido: " + descriptor);
        }
        return file;
    }

    public synchronized void quitar(int descriptor) {
        files.remove(descriptor);
    }

    public synchronized Optional<DfsFile> buscarPorRuta(String path) {
        return files.values().stream().filter(file -> file.obtenerRuta().equals(path)).findFirst();
    }

    public synchronized List<DfsFile> listarArchivos() {
        return List.copyOf(files.values());
    }

    public synchronized void vaciar() {
        files.clear();
    }

    public void registrarBloqueoDeArchivo(String lockId, DfsFile file) {
        fileLocks.put(lockId, file);
    }

    /** Devuelve el archivo dueño del lock, si lo tiene, y lo olvida. */
    public Optional<DfsFile> quitarBloqueoDeArchivo(String lockId) {
        return Optional.ofNullable(fileLocks.remove(lockId));
    }

    public void registrarBloqueoLibre(String lockId) {
        freeLocks.add(lockId);
    }

    public boolean quitarBloqueoLibre(String lockId) {
        return freeLocks.remove(lockId);
    }

    public List<String> listarBloqueosLibres() {
        return List.copyOf(freeLocks);
    }
}
