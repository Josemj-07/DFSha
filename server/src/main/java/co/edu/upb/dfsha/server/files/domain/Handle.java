package co.edu.upb.dfsha.server.files.domain;

import co.edu.upb.dfsha.server.blocks.domain.Block;
import java.time.Instant;
import java.util.*;

public record Handle(
    String id,
    String owner,
    long inodeId,
    String mode,
    long baseVersion,
    List<Block> baseBlocks,
    String lockId,
    Instant expiresAt,
    Map<Integer, Block> pending,
    String lastCommit,
    Long lastVersion) {
  public Handle {
    baseBlocks = List.copyOf(baseBlocks);
    pending = Map.copyOf(pending);
  }
}
