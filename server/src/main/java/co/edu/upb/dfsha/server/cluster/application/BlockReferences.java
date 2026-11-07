package co.edu.upb.dfsha.server.cluster.application;

import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import java.util.*;

public final class BlockReferences {
  private BlockReferences() {}

  public static Map<String, Block> obtener(MetadataState s) {
    Map<String, Block> result = new HashMap<>();
    s.directories.obtenerInodos().stream()
        .distinct()
        .map(s.inodes::get)
        .forEach(i -> i.blocks().forEach(b -> result.put(b.blockId() + ":" + b.gen(), b)));
    s.handles
        .values()
        .forEach(h -> h.baseBlocks().forEach(b -> result.put(b.blockId() + ":" + b.gen(), b)));
    return result;
  }
}
