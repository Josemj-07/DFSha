package co.edu.upb.dfsha.client.metadata;

import java.util.List;

/** Ciclo de vida de un archivo abierto. */
public interface FileSessionGateway {

    OpenResult abrir(OpenRequest request);

    Allocation asignar(String handle, int firstIdx, int count);

    Allocation recuperarBloque(String handle, int idx, List<String> failedNodes);

    long confirmar(String handle, CommitRequest request);

    KeepaliveResult mantenerActivo(String handle);

    void cerrar(String handle);

    void reportarReplica(ReplicaReport report);
}
