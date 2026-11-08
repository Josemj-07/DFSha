package co.edu.upb.dfsha.node.domain;

/** Volver a enviar el registro y el block report completo. */
public record ResyncCommand(String commandId) implements NodeCommand {
}
