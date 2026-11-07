package co.edu.upb.dfsha.server.blocks.domain;

public record Block(int idx, String blockId, long gen, long len, String sha256) {}
