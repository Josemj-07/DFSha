package co.edu.upb.dfsha.server.files.application;

import java.util.List;

public record CommittedBlock(
    int idx, String blockId, long gen, long len, String sha256, List<Receipt> receipts) {}
