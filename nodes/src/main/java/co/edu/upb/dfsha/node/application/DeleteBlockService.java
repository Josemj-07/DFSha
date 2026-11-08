package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.BlockEvent;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.DeleteBlockCommand;
import co.edu.upb.dfsha.node.port.BlockStore;
import co.edu.upb.dfsha.node.port.EventPublisher;

import java.util.Optional;
import java.util.UUID;

/**
 * Borra un bloque y avisa con block.deleted, también si ya no existía: así el servidor deja de
 * contarlo como réplica aunque se haya perdido el aviso de un borrado anterior.
 */
public class DeleteBlockService implements CommandHandler<DeleteBlockCommand> {

    // El servidor exige length y sha256 en el evento; si el bloque ya no estaba, no se conocen.
    private static final String SHA_DESCONOCIDO = "0".repeat(64);

    private final String nodeId;
    private final BlockStore store;
    private final EventPublisher eventos;

    public DeleteBlockService(String nodeId, BlockStore store, EventPublisher eventos) {
        this.nodeId = nodeId;
        this.store = store;
        this.eventos = eventos;
    }

    @Override
    public void ejecutar(DeleteBlockCommand comando) {
        Optional<BlockMeta> meta = store.buscar(comando.key());
        store.borrar(comando.key());
        var evento = meta
                .map(m -> BlockEvent.de(UUID.randomUUID().toString(), nodeId, m))
                .orElseGet(() -> new BlockEvent(UUID.randomUUID().toString(), nodeId, comando.key().blockId(),
                        comando.key().gen(), 0, SHA_DESCONOCIDO));
        eventos.publicarDurable("block.deleted." + nodeId, evento);
    }
}
