package co.edu.upb.dfsha.node.domain;

/** Cuerpo de node.register.&lt;id&gt;. Con certPem el servidor comprueba la identidad y verifica recibos. */
public record NodeRegistration(String nodeId, String host, int clientPort, int replicationPort, String az,
                               long capacityBytes, String certPem) {

    public static NodeRegistration de(NodeIdentity identidad, long capacityBytes) {
        return new NodeRegistration(identidad.nodeId(), identidad.host(), identidad.clientPort(),
                identidad.replicationPort(), identidad.az(), capacityBytes, identidad.certPem());
    }
}
