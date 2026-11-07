package co.edu.upb.dfsha.server.cluster.domain;

public record ReplicateBlock(
    String commandId, String type, String blockId, long gen, ReplicationTarget target, String token)
    implements NodeCommand {
  public ReplicateBlock(
      String commandId, String blockId, long gen, ReplicationTarget target, String token) {
    this(commandId, "REPLICATE_BLOCK", blockId, gen, target, token);
  }
}
