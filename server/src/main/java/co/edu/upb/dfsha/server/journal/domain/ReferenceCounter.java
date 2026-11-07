package co.edu.upb.dfsha.server.journal.domain;

import co.edu.upb.dfsha.server.blocks.domain.BlockMetadata;
import java.time.Instant;
import java.util.*;

public final class ReferenceCounter {
  private ReferenceCounter() {}

  public static void actualizar(MetadataState state, Instant now) {
    Map<String, Long> counts = new HashMap<>();
    state.directories.obtenerInodos().stream()
        .distinct()
        .map(state.inodes::get)
        .forEach(
            inode ->
                inode
                    .blocks()
                    .forEach(
                        block -> {
                          String key = block.blockId() + ":" + block.gen();
                          counts.merge(key, 1L, Long::sum);
                          state.blocks.put(
                              key, new BlockMetadata(block.gen(), block.len(), block.sha256(), 0));
                        }));
    state.blocks.replaceAll(
        (key, block) -> {
          long count = counts.getOrDefault(key, 0L);
          if (count == 0) state.unreferenced.putIfAbsent(key, now);
          else state.unreferenced.remove(key);
          return new BlockMetadata(block.gen(), block.len(), block.sha256(), count);
        });
  }
}
