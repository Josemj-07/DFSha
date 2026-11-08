package co.edu.upb.dfsha.node.domain;

/** Orden del servidor a este nodo. commandId permite ignorar entregas repetidas. */
public sealed interface NodeCommand permits ReplicateBlockCommand, DeleteBlockCommand, ResyncCommand {

    String commandId();
}
