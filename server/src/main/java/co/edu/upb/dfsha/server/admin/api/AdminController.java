package co.edu.upb.dfsha.server.admin.api;

import co.edu.upb.dfsha.server.admin.application.AdminService;
import co.edu.upb.dfsha.server.audit.domain.AuditEntry;
import co.edu.upb.dfsha.server.auth.application.CurrentUser;
import co.edu.upb.dfsha.server.cluster.application.ClusterState;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {
  private final AdminService admin;
  private final CurrentUser current;
  private final ClusterState cluster;
  private final MetadataStore store;

  public AdminController(
      AdminService admin, CurrentUser current, ClusterState cluster, MetadataStore store) {
    this.admin = admin;
    this.current = current;
    this.cluster = cluster;
    this.store = store;
  }

  public record CreateUser(String username, String password, String role) {}

  public record Quota(long quotaBytes) {}

  public record NodeInfo(
      String nodeId, String addr, String az, String status, long capacityBytes, long usedBytes) {}

  @PostMapping("/users")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void crear(@RequestBody CreateUser body) {
    admin.crear(body.username(), body.password(), body.role(), current.obtenerNombre());
  }

  @PutMapping("/users/{user}/quota")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void actualizarCuota(@PathVariable String user, @RequestBody Quota body) {
    admin.actualizarCuota(user, body.quotaBytes(), current.obtenerNombre());
  }

  @GetMapping("/nodes")
  public List<NodeInfo> listarNodos() {
    return cluster.obtenerNodos().stream()
        .map(
            n ->
                new NodeInfo(
                    n.nodeId(),
                    n.obtenerDireccionCliente(),
                    n.az(),
                    n.status(),
                    n.capacityBytes(),
                    n.usedBytes()))
        .toList();
  }

  @GetMapping("/audit")
  public List<AuditEntry> listarAuditoria() {
    return store.leer(s -> List.copyOf(s.audit));
  }
}
