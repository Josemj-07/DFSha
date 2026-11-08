package co.edu.upb.dfsha.node.port;

import co.edu.upb.dfsha.proto.v1.WriteResponse;

/** Avisos que llegan desde el siguiente nodo de la cadena. */
public interface ReplicaListener {

    /** El siguiente nodo volvió a aceptar datos. */
    void alEstarListo();

    /** Recibos del siguiente nodo y de los que vienen después de él. */
    void alResponder(WriteResponse respuesta);

    /** El siguiente nodo terminó bien. */
    void alTerminar();

    /** La cadena falló. */
    void alFallar(ReplicaFailure falla);
}
