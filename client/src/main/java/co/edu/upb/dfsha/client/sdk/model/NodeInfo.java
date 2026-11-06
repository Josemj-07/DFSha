package co.edu.upb.dfsha.client.sdk.model;

/** Estado de un DataNode, para administradores. */
public record NodeInfo(String nodeId, String addr, String az, String status, long capacityBytes, long usedBytes) {
}
