package co.edu.upb.dfsha.server.journal.domain;

import co.edu.upb.dfsha.server.audit.domain.AuditEntry;
import co.edu.upb.dfsha.server.auth.domain.*;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.locks.domain.FileLock;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import java.time.Instant;
import java.util.*;

public class MetadataState {
  public co.edu.upb.dfsha.server.namespace.domain.DirectoryIndex directories =
      new co.edu.upb.dfsha.server.namespace.domain.DirectoryIndex();
  public Map<Long, Inode> inodes = new HashMap<>();
  public Map<String, Handle> handles = new HashMap<>();
  public Map<String, FileLock> locks = new HashMap<>();
  public Map<String, User> users = new HashMap<>();
  public Map<String, RefreshSession> refreshTokens = new HashMap<>();
  public Map<String, Instant> pendingBlocks = new HashMap<>();
  public Map<String, Instant> unreferenced = new HashMap<>();
  public List<AuditEntry> audit = new ArrayList<>();
  public Map<String, co.edu.upb.dfsha.server.blocks.domain.BlockMetadata> blocks = new HashMap<>();
  public long fencingSequence;
  public long inodeSequence;

  public MetadataState copiar() {
    var copy = new MetadataState();
    copy.directories = directories.copiar();
    copy.inodes.putAll(inodes);
    copy.handles.putAll(handles);
    copy.locks.putAll(locks);
    copy.users.putAll(users);
    copy.refreshTokens.putAll(refreshTokens);
    copy.pendingBlocks.putAll(pendingBlocks);
    copy.unreferenced.putAll(unreferenced);
    copy.audit.addAll(audit);
    copy.blocks.putAll(blocks);
    copy.fencingSequence = fencingSequence;
    copy.inodeSequence = inodeSequence;
    return copy;
  }
}
