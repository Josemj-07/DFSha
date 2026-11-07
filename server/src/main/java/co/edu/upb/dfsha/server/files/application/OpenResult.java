package co.edu.upb.dfsha.server.files.application;

import java.util.List;

public record OpenResult(
    String handle,
    long baseVersion,
    long blockSize,
    long size,
    String fileSha256,
    Lease lease,
    String readToken,
    List<BlockInfo> blocks) {}
