package co.edu.upb.dfsha.server.journal.op;

import co.edu.upb.dfsha.server.journal.domain.MetadataState;

public sealed interface JournalOp
    permits MetadataChange,
        CreateDir,
        DeleteDir,
        Move,
        CopyFile,
        DeleteFile,
        OpenHandle,
        CloseHandle,
        CommitVersion,
        AllocateBlocks,
        AcquireLock,
        ReleaseLock,
        CreateUser,
        UpdateQuota,
        RefreshTokens,
        AuditEntry {
  void aplicar(MetadataState state);
}
