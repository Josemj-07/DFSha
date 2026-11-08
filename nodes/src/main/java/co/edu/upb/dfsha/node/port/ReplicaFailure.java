package co.edu.upb.dfsha.node.port;

import java.util.List;

/**
 * Falla del siguiente nodo.
 *
 * @param codigo nombre del código gRPC (por ejemplo ABORTED o ALREADY_EXISTS)
 * @param nodosFallidos nodos que causaron la falla
 */
public record ReplicaFailure(String codigo, List<String> nodosFallidos) {

    public ReplicaFailure {
        nodosFallidos = List.copyOf(nodosFallidos);
    }

    /** El destino ya tenía el bloque completo: para una re-replicación eso es un éxito. */
    public boolean yaExistia() {
        return "ALREADY_EXISTS".equals(codigo);
    }
}
