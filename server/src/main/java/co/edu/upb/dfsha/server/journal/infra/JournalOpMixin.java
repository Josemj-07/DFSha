package co.edu.upb.dfsha.server.journal.infra;

import co.edu.upb.dfsha.server.journal.op.*;
import com.fasterxml.jackson.annotation.*;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
@JsonSubTypes({
  @JsonSubTypes.Type(value = MetadataChange.class, name = "metadata-change-v1"),
  @JsonSubTypes.Type(value = CreateDir.class, name = "create-dir-v1"),
  @JsonSubTypes.Type(value = DeleteDir.class, name = "delete-dir-v1"),
  @JsonSubTypes.Type(value = Move.class, name = "move-v1"),
  @JsonSubTypes.Type(value = CopyFile.class, name = "copy-file-v1"),
  @JsonSubTypes.Type(value = DeleteFile.class, name = "delete-file-v1"),
  @JsonSubTypes.Type(value = OpenHandle.class, name = "open-handle-v1"),
  @JsonSubTypes.Type(value = CloseHandle.class, name = "close-handle-v1"),
  @JsonSubTypes.Type(value = CommitVersion.class, name = "commit-version-v1"),
  @JsonSubTypes.Type(value = AllocateBlocks.class, name = "allocate-blocks-v1"),
  @JsonSubTypes.Type(value = AcquireLock.class, name = "acquire-lock-v1"),
  @JsonSubTypes.Type(value = ReleaseLock.class, name = "release-lock-v1"),
  @JsonSubTypes.Type(value = CreateUser.class, name = "create-user-v1"),
  @JsonSubTypes.Type(value = UpdateQuota.class, name = "update-quota-v1"),
  @JsonSubTypes.Type(value = RefreshTokens.class, name = "refresh-tokens-v1"),
  @JsonSubTypes.Type(value = AuditEntry.class, name = "audit-entry-v1")
})
abstract class JournalOpMixin {}
