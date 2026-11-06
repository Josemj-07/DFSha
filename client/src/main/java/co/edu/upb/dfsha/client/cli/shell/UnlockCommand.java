package co.edu.upb.dfsha.client.cli.shell;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;

import co.edu.upb.dfsha.client.sdk.DfsFile;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Libera un lock. */
@Command(name = "unlock", mixinStandardHelpOptions = true, description = "Libera un lock.")
public final class UnlockCommand extends ClientCommand {

    private final DescriptorTable table;

    @Parameters(index = "0", paramLabel = "ID", description = "Identificador del lock")
    private String lockId;

    public UnlockCommand(ClientContext context, DescriptorTable table) {
        super(context);
        this.table = table;
    }

    @Override
    protected void ejecutar() {
        Optional<DfsFile> owner = table.quitarBloqueoDeArchivo(lockId);
        if (owner.isPresent()) {
            owner.get().desbloquear(lockId);
            return;
        }
        table.quitarBloqueoLibre(lockId);
        cliente().desbloquear(lockId);
    }
}
