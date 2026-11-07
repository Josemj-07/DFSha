package co.edu.upb.dfsha.server.cluster.domain;

public record DeleteBlock(String commandId, String type, String blockId, long gen)
    implements NodeCommand {
  public DeleteBlock(String commandId, String blockId, long gen) {
    this(commandId, "DELETE_BLOCK", blockId, gen);
  }
}
