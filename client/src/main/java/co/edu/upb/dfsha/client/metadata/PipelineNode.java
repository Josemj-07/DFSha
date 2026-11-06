package co.edu.upb.dfsha.client.metadata;

/** Nodo de la cadena de escritura. */
public record PipelineNode(String nodeId, String clientAddr, String replicationAddr) {
}
