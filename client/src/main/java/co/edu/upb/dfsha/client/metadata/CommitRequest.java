package co.edu.upb.dfsha.client.metadata;

import java.util.List;

/** Solicitud de publicación de una versión. */
public record CommitRequest(String fencingToken, long size, String fileSha256, List<CommittedBlock> blocks) {
}
