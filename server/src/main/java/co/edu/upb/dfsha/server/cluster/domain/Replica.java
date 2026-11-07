package co.edu.upb.dfsha.server.cluster.domain;

public record Replica(String nodeId, String blockId, long gen, long length, String sha256) {}
