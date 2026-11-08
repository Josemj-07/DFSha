package co.edu.upb.dfsha.node.domain;

import java.util.Objects;

/** Cómo se presenta este nodo al servidor: identidad, dirección publicada y certificado. */
public record NodeIdentity(String nodeId, String host, int clientPort, int replicationPort, String az,
                           String certPem) {

    public NodeIdentity {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(az, "az");
        Objects.requireNonNull(certPem, "certPem");
        exigirPuerto(clientPort);
        exigirPuerto(replicationPort);
    }

    private static void exigirPuerto(int puerto) {
        if (puerto < 1 || puerto > 65535) {
            throw new IllegalArgumentException("puerto inválido: " + puerto);
        }
    }
}
