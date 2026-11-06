package co.edu.upb.dfsha.client.metadata;

import co.edu.upb.dfsha.client.sdk.model.AuditEntry;
import co.edu.upb.dfsha.client.sdk.model.NodeInfo;
import java.util.List;

/** Operaciones reservadas al rol ADMIN. */
public interface AdminGateway {

    void crearUsuario(String username, char[] password, String role);

    void asignarCuota(String username, long quotaBytes);

    List<NodeInfo> listarNodos();

    List<AuditEntry> listarAuditoria();
}
