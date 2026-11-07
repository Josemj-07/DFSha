package co.edu.upb.dfsha.server.cluster.domain;

public sealed interface NodeCommand permits ReplicateBlock, DeleteBlock, Resync {
  String commandId();

  String type();
}
