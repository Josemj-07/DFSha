package co.edu.upb.dfsha.client.metadata;

import static co.edu.upb.dfsha.client.metadata.QueryString.codificar;

import co.edu.upb.dfsha.client.sdk.model.AuditEntry;
import co.edu.upb.dfsha.client.sdk.model.NodeInfo;
import java.util.List;
import java.util.Map;

/** Administración por REST. */
public final class RestAdminGateway implements AdminGateway {

    private final RestInvoker rest;

    public RestAdminGateway(RestInvoker rest) {
        this.rest = rest;
    }

    @Override
    public void crearUsuario(String username, char[] password, String role) {
        var body = Map.of("username", username, "password", new String(password), "role", role);
        rest.ejecutarSinRespuesta(RestRequest.enviar("/admin/users", body));
    }

    @Override
    public void asignarCuota(String username, long quotaBytes) {
        rest.ejecutarSinRespuesta(RestRequest.actualizar(
                "/admin/users/" + codificar(username) + "/quota", Map.of("quotaBytes", quotaBytes)));
    }

    @Override
    public List<NodeInfo> listarNodos() {
        return rest.ejecutarLista(RestRequest.consultar("/admin/nodes"), NodeInfo.class);
    }

    @Override
    public List<AuditEntry> listarAuditoria() {
        return rest.ejecutarLista(RestRequest.consultar("/admin/audit"), AuditEntry.class);
    }
}
