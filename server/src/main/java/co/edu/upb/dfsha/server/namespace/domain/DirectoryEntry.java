package co.edu.upb.dfsha.server.namespace.domain;

public record DirectoryEntry(long parentInode, String name) {
  public DirectoryEntry(String encoded) {
    this(
        Long.parseLong(encoded.substring(0, encoded.indexOf('/'))),
        encoded.substring(encoded.indexOf('/') + 1));
  }

  @Override
  public String toString() {
    return parentInode + "/" + name;
  }
}
