package co.edu.upb.dfsha.server.locks.domain;

import java.time.Instant;

public record FileLock(
    String id,
    String owner,
    long inodeId,
    String mode,
    BlockRange range,
    String fencingToken,
    Instant expiresAt,
    long ttlMs) {}
