package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.BlockReport;
import co.edu.upb.dfsha.node.domain.NodeIdentity;
import co.edu.upb.dfsha.node.domain.NodeRegistration;
import co.edu.upb.dfsha.node.domain.ReportedBlock;
import co.edu.upb.dfsha.node.port.BlockStore;
import co.edu.upb.dfsha.node.port.EventPublisher;

/**
 * Registra el nodo y le cuenta al servidor qué bloques tiene. Se ejecuta al arrancar, al
 * reconectar con RabbitMQ, al recibir RESYNC y cada hora. Así el servidor reconstruye el mapa de
 * réplicas aunque haya perdido eventos o se haya reiniciado.
 */
public class BlockReportService {

    private final NodeIdentity identidad;
    private final BlockStore store;
    private final EventPublisher eventos;

    public BlockReportService(NodeIdentity identidad, BlockStore store, EventPublisher eventos) {
        this.identidad = identidad;
        this.store = store;
        this.eventos = eventos;
    }

    /** El registro va primero: el servidor ignora los reportes de un nodo que no conoce. */
    public void publicarTodo() {
        publicarRegistro();
        publicarReporte();
    }

    public void publicarRegistro() {
        var registro = NodeRegistration.de(identidad, store.obtenerBytesCapacidad());
        eventos.publicarDurable("node.register." + identidad.nodeId(), registro);
    }

    public void publicarReporte() {
        var bloques = store.listar().stream().map(ReportedBlock::de).toList();
        eventos.publicarDurable("node.report." + identidad.nodeId(), new BlockReport(identidad.nodeId(), bloques));
    }
}
