package co.edu.upb.dfsha.server.cluster.infra;

import co.edu.upb.dfsha.server.auth.application.CurrentUser;
import co.edu.upb.dfsha.server.cluster.application.ClusterState;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReplicaReportController {
  private final ClusterState cluster;
  private final MetadataStore store;
  private final CurrentUser current;

  public ReplicaReportController(ClusterState cluster, MetadataStore store, CurrentUser current) {
    this.cluster = cluster;
    this.store = store;
    this.current = current;
  }

  public record Report(String blockId, long gen, String node, String reason) {}

  @PostMapping("/v1/reports/replica")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reportar(@RequestBody Report report) {
    DfsException.exigir(
        "CORRUPT".equals(report.reason()) || "UNREACHABLE".equals(report.reason()),
        400,
        "INVALID_REQUEST",
        "Motivo invalido");
    boolean authorized =
        store.leer(
            s ->
                s.inodes.values().stream()
                    .filter(
                        i -> i.owner().equals(current.obtenerNombre()) || current.esAdministrador())
                    .flatMap(i -> i.blocks().stream())
                    .anyMatch(
                        b -> b.blockId().equals(report.blockId()) && b.gen() == report.gen()));
    DfsException.exigir(authorized, 403, "FORBIDDEN", "Bloque fuera del alcance");
    DfsException.exigir(
        cluster.obtenerNodo(report.node()) != null, 400, "INVALID_REQUEST", "Nodo desconocido");
    if (report.reason().equals("CORRUPT"))
      cluster.quitar(report.blockId(), report.gen(), report.node(), true);
    else cluster.marcarInalcanzable(report.blockId(), report.gen(), report.node());
  }
}
