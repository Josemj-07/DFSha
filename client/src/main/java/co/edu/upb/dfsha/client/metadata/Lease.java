package co.edu.upb.dfsha.client.metadata;

/** Lease de un handle. En lectura no lleva fencing token. */
public record Lease(String fencingToken, long ttlMs) {
}
