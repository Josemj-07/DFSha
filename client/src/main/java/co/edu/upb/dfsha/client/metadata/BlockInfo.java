package co.edu.upb.dfsha.client.metadata;

import co.edu.upb.dfsha.client.sdk.model.ReplicaRef;
import java.util.List;

/** Bloque confirmado de un archivo y sus réplicas vivas. */
public record BlockInfo(int idx, String blockId, long gen, long len, String sha256, List<ReplicaRef> replicas) {
}
