package co.edu.upb.dfsha.node.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuración del nodo (prefijo "dfs" en application.properties). Todo valor de entorno llega
 * por aquí: en el código no hay direcciones, rutas ni identidades fijas.
 */
@ConfigurationProperties("dfs")
public record NodeProperties(String nodeId, String az, String advertisedHost, int clientPort, int replicationPort,
                             Path dataDir, Path tlsDir, Duration heartbeatInterval, Duration scrubInterval,
                             long repairRateMbPerSec) {

    public NodeProperties {
        exigirTexto(nodeId, "DFS_NODE_ID");
        exigirTexto(az, "DFS_NODE_AZ");
        exigirTexto(advertisedHost, "DFS_ADVERTISED_HOST");
        if (dataDir == null || tlsDir == null) {
            throw new IllegalArgumentException("faltan DFS_DATA_DIR o DFS_TLS_DIR");
        }
        if (repairRateMbPerSec <= 0) {
            throw new IllegalArgumentException("dfs.repair-rate-mb-per-sec debe ser positivo");
        }
    }

    public Path obtenerCertificado() {
        return tlsDir.resolve("tls.crt");
    }

    public Path obtenerClavePrivada() {
        return tlsDir.resolve("tls.key");
    }

    public Path obtenerCa() {
        return tlsDir.resolve("ca.crt");
    }

    public Path obtenerClaveTokens() {
        return tlsDir.resolve("blocktoken.pub");
    }

    private static void exigirTexto(String valor, String variable) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("falta la variable de entorno " + variable);
        }
    }
}
