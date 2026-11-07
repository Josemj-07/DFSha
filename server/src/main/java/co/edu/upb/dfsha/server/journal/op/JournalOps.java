package co.edu.upb.dfsha.server.journal.op;

import co.edu.upb.dfsha.server.journal.domain.StateChange;
import java.util.Map;
import java.util.function.Function;

public final class JournalOps {
  private JournalOps() {}

  private static final Map<String, Function<StateChange, JournalOp>> FACTORIES =
      Map.ofEntries(
          Map.entry("metadata-change", MetadataChange::new),
          Map.entry("create-dir", CreateDir::new),
          Map.entry("delete-dir", DeleteDir::new),
          Map.entry("move", Move::new),
          Map.entry("copy-file", CopyFile::new),
          Map.entry("delete-file", DeleteFile::new),
          Map.entry("open-handle", OpenHandle::new),
          Map.entry("close-handle", CloseHandle::new),
          Map.entry("commit-version", CommitVersion::new),
          Map.entry("allocate-blocks", AllocateBlocks::new),
          Map.entry("acquire-lock", AcquireLock::new),
          Map.entry("release-lock", ReleaseLock::new),
          Map.entry("create-user", CreateUser::new),
          Map.entry("update-quota", UpdateQuota::new),
          Map.entry("refresh-tokens", RefreshTokens::new),
          Map.entry("audit-entry", AuditEntry::new));

  public static JournalOp crear(String type, StateChange change) {
    var factory = FACTORIES.get(type);
    if (factory == null) throw new IllegalArgumentException("Operacion WAL desconocida");
    return factory.apply(change);
  }
}
