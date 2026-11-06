package co.edu.upb.dfsha.client.metadata;

import java.util.List;

/** Respuesta de apertura. */
public record OpenResult(String handle, long baseVersion, long blockSize, long size, String fileSha256,
                         Lease lease, String readToken, List<BlockInfo> blocks) {
}
