package co.edu.upb.dfsha.server.namespace.domain;

import co.edu.upb.dfsha.server.blocks.domain.Block;
import java.time.Instant;
import java.util.List;

public record Inode(
    long id,
    String owner,
    String mode,
    boolean directory,
    long size,
    long version,
    String fileSha256,
    Instant ctime,
    Instant mtime,
    List<Block> blocks) {
  public Inode {
    blocks = List.copyOf(blocks);
  }
}
