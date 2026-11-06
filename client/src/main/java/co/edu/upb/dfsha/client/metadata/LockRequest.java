package co.edu.upb.dfsha.client.metadata;

import co.edu.upb.dfsha.client.sdk.model.BlockRange;

/** Solicitud de un lock. Un rango nulo cubre todo el archivo. */
public record LockRequest(String path, String mode, BlockRange range, long ttlMs, long waitMs) {
}
