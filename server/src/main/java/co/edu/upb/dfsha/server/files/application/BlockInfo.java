package co.edu.upb.dfsha.server.files.application;

import java.util.List;

public record BlockInfo(
    int idx, String blockId, long gen, long len, String sha256, List<ReplicaRef> replicas) {}
