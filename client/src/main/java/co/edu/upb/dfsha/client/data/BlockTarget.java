package co.edu.upb.dfsha.client.data;

import java.util.List;

/**
 * Destino de la escritura de un bloque.
 *
 * @param firstAddr dirección host:puerto del primer nodo
 * @param firstNode identificador del primer nodo
 * @param downstream nodos restantes de la cadena, en orden
 */
public record BlockTarget(String blockId, long gen, long length, String firstNode, String firstAddr,
                          List<Hop> downstream) {

    /** Salto de la cadena hacia otro nodo. */
    public record Hop(String nodeId, String replicationAddr) {
    }
}
