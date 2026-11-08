package co.edu.upb.dfsha.node.domain;

/** Borrar un bloque local. */
public record DeleteBlockCommand(String commandId, BlockKey key) implements NodeCommand {
}
