package co.edu.upb.dfsha.server.cluster.domain;

public record Resync(String commandId, String type) implements NodeCommand {
  public Resync(String commandId) {
    this(commandId, "RESYNC");
  }
}
