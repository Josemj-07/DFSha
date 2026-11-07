package co.edu.upb.dfsha.server.audit.domain;

import co.edu.upb.dfsha.server.auth.domain.User;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import java.util.*;

public final class UsageAccounting {
  private UsageAccounting() {}

  public static void actualizar(MetadataState state) {
    Map<String, Long> usage = new HashMap<>();
    state.directories.obtenerInodos().stream()
        .distinct()
        .map(state.inodes::get)
        .forEach(inode -> usage.merge(inode.owner(), inode.size(), Long::sum));
    state.users.replaceAll(
        (name, user) ->
            new User(
                name,
                user.passwordHash(),
                user.role(),
                user.quotaBytes(),
                usage.getOrDefault(name, 0L)));
  }
}
