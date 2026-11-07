package co.edu.upb.dfsha.server.blocks.domain;

public record BlockMetadata(long gen, long len, String sha256, long refCount) {}
