package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.Heartbeat;
import co.edu.upb.dfsha.node.port.BlockStore;
import co.edu.upb.dfsha.node.port.EventPublisher;

import java.time.Clock;
import java.util.function.IntSupplier;

/**
 * Avisa al servidor que el nodo sigue vivo. El detector de fallas del servidor marca SUSPECT a
 * los 10 s y DEAD a los 30 s sin heartbeats, así que se envían cada 3 s.
 */
public class HeartbeatService {

    private final String nodeId;
    private final BlockStore store;
    private final EventPublisher eventos;
    private final IntSupplier conexionesActivas;
    private final Clock clock;

    public HeartbeatService(String nodeId, BlockStore store, EventPublisher eventos, IntSupplier conexionesActivas,
                            Clock clock) {
        this.nodeId = nodeId;
        this.store = store;
        this.eventos = eventos;
        this.conexionesActivas = conexionesActivas;
        this.clock = clock;
    }

    /** Un heartbeat perdido no importa: llega otro en 3 s. Por eso no pasa por el outbox. */
    public void latir() {
        eventos.publicarVolatil("node.heartbeat." + nodeId, construir());
    }

    Heartbeat construir() {
        long capacidad = store.obtenerBytesCapacidad();
        long usados = Math.max(0, capacidad - store.obtenerBytesDisponibles());
        return new Heartbeat(nodeId, capacidad, usados, conexionesActivas.getAsInt(), store.listar().size(),
                clock.instant().toString());
    }
}
