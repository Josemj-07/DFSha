package co.edu.upb.dfsha.server.journal.domain;

import co.edu.upb.dfsha.server.audit.domain.AuditEntry;
import co.edu.upb.dfsha.server.auth.domain.*;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.locks.domain.FileLock;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import java.time.Instant;
import java.util.*;

public record StateChange(
    Map<String, co.edu.upb.dfsha.server.blocks.domain.BlockMetadata> blocks,
    Map<co.edu.upb.dfsha.server.namespace.domain.DirectoryEntry, Long> paths,
    Map<Long, Inode> inodes,
    Map<String, Handle> handles,
    Map<String, FileLock> locks,
    Map<String, User> users,
    Map<String, RefreshSession> refresh,
    Map<String, Instant> pending,
    Map<String, Instant> unreferenced,
    List<AuditEntry> audit,
    long fencingSequence,
    long inodeSequence) {
  public void aplicar(MetadataState state) {
    aplicarMapa(state.blocks, blocks);
    aplicarMapa(state.directories.entries, paths);
    aplicarMapa(state.inodes, inodes);
    aplicarMapa(state.handles, handles);
    aplicarMapa(state.locks, locks);
    aplicarMapa(state.users, users);
    aplicarMapa(state.refreshTokens, refresh);
    aplicarMapa(state.pendingBlocks, pending);
    aplicarMapa(state.unreferenced, unreferenced);
    state.audit.addAll(audit);
    state.fencingSequence = fencingSequence;
    state.inodeSequence = inodeSequence;
  }

  private static <K, V> void aplicarMapa(Map<K, V> target, Map<K, V> changes) {
    changes.forEach(
        (k, v) -> {
          if (v == null) target.remove(k);
          else target.put(k, v);
        });
  }

  public static StateChange entre(MetadataState before, MetadataState after) {
    return new StateChange(
        cambios(before.blocks, after.blocks),
        cambios(before.directories.entries, after.directories.entries),
        cambios(before.inodes, after.inodes),
        cambios(before.handles, after.handles),
        cambios(before.locks, after.locks),
        cambios(before.users, after.users),
        cambios(before.refreshTokens, after.refreshTokens),
        cambios(before.pendingBlocks, after.pendingBlocks),
        cambios(before.unreferenced, after.unreferenced),
        List.copyOf(after.audit.subList(before.audit.size(), after.audit.size())),
        after.fencingSequence,
        after.inodeSequence);
  }

  private static <K, V> Map<K, V> cambios(Map<K, V> before, Map<K, V> after) {
    Map<K, V> result = new HashMap<>();
    Set<K> keys = new HashSet<>(before.keySet());
    keys.addAll(after.keySet());
    for (K key : keys)
      if (!Objects.equals(before.get(key), after.get(key))) result.put(key, after.get(key));
    return result;
  }
}
