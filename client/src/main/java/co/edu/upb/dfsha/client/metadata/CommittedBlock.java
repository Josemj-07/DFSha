package co.edu.upb.dfsha.client.metadata;

import java.util.List;

/** Bloque nuevo incluido en una publicación. */
public record CommittedBlock(int idx, String blockId, long gen, long len, String sha256, List<BlockReceipt> receipts) {
}
