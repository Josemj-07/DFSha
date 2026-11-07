package co.edu.upb.dfsha.server.support;

import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import java.time.Clock;
import java.util.List;

public final class TestNamespace {
  private TestNamespace() {}

  public static void crearHome(MetadataState state, String owner, Clock clock) {
    for (String path : List.of("/", "/home", "/home/" + owner)) {
      long id = ++state.inodeSequence + 10000;
      state.directories.poner(path, id);
      state.inodes.put(
          id,
          new Inode(
              id,
              owner,
              "rwx------",
              true,
              0,
              0,
              null,
              clock.instant(),
              clock.instant(),
              List.of()));
    }
  }
}
