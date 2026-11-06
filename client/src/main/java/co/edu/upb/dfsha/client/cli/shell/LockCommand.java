package co.edu.upb.dfsha.client.cli.shell;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.sdk.DfsFile;
import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import co.edu.upb.dfsha.client.sdk.model.BlockRange;
import co.edu.upb.dfsha.client.sdk.model.LockHandle;
import co.edu.upb.dfsha.client.sdk.model.LockMode;
import java.time.Duration;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Toma un lock sobre un archivo o un rango de bloques. */
@Command(name = "lock", mixinStandardHelpOptions = true, description = "Toma un lock sobre un archivo o rango.")
public final class LockCommand extends ClientCommand {

    private final DescriptorTable table;

    @Parameters(index = "0", paramLabel = "RUTA|FD", description = "Ruta o descriptor abierto")
    private String target;

    @Option(names = "--mode", required = true, paramLabel = "MODO", description = "shared o exclusive")
    private String mode;

    @Option(names = "--range", paramLabel = "A-B", description = "Rango de bloques; sin él, todo el archivo")
    private String range;

    @Option(names = "--ttl", paramLabel = "MS", description = "Duración en milisegundos (30000 por defecto)")
    private long ttlMs = 30000;

    @Option(names = "--wait", paramLabel = "MS", description = "Espera máxima en cola (0 por defecto)")
    private long waitMs;

    public LockCommand(ClientContext context, DescriptorTable table) {
        super(context);
        this.table = table;
    }

    @Override
    protected void ejecutar() {
        LockMode lockMode = interpretarModo();
        BlockRange blocks = interpretarRango();
        Duration ttl = Duration.ofMillis(ttlMs);
        Duration wait = Duration.ofMillis(waitMs);
        Optional<DfsFile> file = buscarArchivo();
        LockHandle lock = file.isPresent()
                ? bloquearArchivo(file.get(), lockMode, blocks, ttl, wait)
                : bloquearRuta(lockMode, blocks, ttl, wait);
        salida().println("Lock " + lock.lockId() + ", fencing " + lock.fencingToken());
    }

    private LockHandle bloquearArchivo(DfsFile file, LockMode lockMode, BlockRange blocks, Duration ttl, Duration wait) {
        LockHandle lock = file.bloquear(lockMode, blocks, ttl, wait);
        table.registrarBloqueoDeArchivo(lock.lockId(), file);
        return lock;
    }

    private LockHandle bloquearRuta(LockMode lockMode, BlockRange blocks, Duration ttl, Duration wait) {
        LockHandle lock = cliente().bloquear(target, lockMode, blocks, ttl, wait);
        table.registrarBloqueoLibre(lock.lockId());
        return lock;
    }

    /** Un lock pedido por ruta se asocia al descriptor abierto, para que sirva a su publicación. */
    private Optional<DfsFile> buscarArchivo() {
        if (target.matches("\\d+")) {
            return Optional.of(table.obtener(Integer.parseInt(target)));
        }
        return table.buscarPorRuta(cliente().resolver(target));
    }

    private LockMode interpretarModo() {
        return switch (mode.toLowerCase()) {
            case "shared" -> LockMode.SHARED;
            case "exclusive" -> LockMode.EXCLUSIVE;
            default -> throw new InvalidOperationException("INVALID_MODE", "Modo de lock inválido: " + mode);
        };
    }

    private BlockRange interpretarRango() {
        if (range == null) {
            return null;
        }
        String[] limits = range.split("-");
        if (limits.length != 2) {
            throw new InvalidOperationException("INVALID_RANGE", "Rango inválido: " + range + ". Usa A-B");
        }
        return new BlockRange(Integer.parseInt(limits[0]), Integer.parseInt(limits[1]));
    }
}
