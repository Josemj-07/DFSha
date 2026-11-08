package co.edu.upb.dfsha.node.port;

import co.edu.upb.dfsha.proto.v1.Downstream;

/** Abre el stream de escritura hacia el siguiente nodo de la cadena (puerto 50052). */
public interface ReplicaClient {

    /**
     * @param token el mismo block token que trajo el cliente: el siguiente nodo lo valida igual
     * @param listener recibe los avisos del siguiente nodo; puede llamarse desde otro hilo
     */
    ReplicaStream abrir(Downstream target, String token, ReplicaListener listener);
}
