package co.edu.upb.dfsha.server.files.application;

import java.util.List;

public record CommitRequest(
    String fencingToken, long size, String fileSha256, List<CommittedBlock> blocks) {}
