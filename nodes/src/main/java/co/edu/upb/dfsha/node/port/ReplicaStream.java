package co.edu.upb.dfsha.node.port;

import co.edu.upb.dfsha.proto.v1.WriteRequest;

/** Stream de salida hacia el siguiente nodo. */
public interface ReplicaStream {

    /** Falso si el siguiente nodo todavía no puede recibir más: hay que esperar a alEstarListo(). */
    boolean estaListo();

    void enviar(WriteRequest mensaje);

    /** Indica que ya no se enviará nada más; a partir de aquí se espera la respuesta. */
    void completar();

    void cancelar(String motivo);
}
