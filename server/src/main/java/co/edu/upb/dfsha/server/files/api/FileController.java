package co.edu.upb.dfsha.server.files.api;

import co.edu.upb.dfsha.server.files.application.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class FileController {
  private final OpenFileService open;
  private final AllocationService allocation;
  private final CommitService commit;
  private final HandleLifecycle lifecycle;

  public FileController(
      OpenFileService open,
      AllocationService allocation,
      CommitService commit,
      HandleLifecycle lifecycle) {
    this.open = open;
    this.allocation = allocation;
    this.commit = commit;
    this.lifecycle = lifecycle;
  }

  @PostMapping("/files:open")
  public OpenResult abrir(@RequestBody OpenRequest request) {
    return open.abrir(request);
  }

  @PostMapping("/files/{id}:allocate")
  public Allocation asignar(@PathVariable String id, @RequestBody AllocateRequest request) {
    return allocation.asignar(id, request.firstIdx(), request.count());
  }

  @PostMapping("/files/{id}:recover-block")
  public Allocation recuperarBloque(@PathVariable String id, @RequestBody RecoverRequest request) {
    return allocation.recuperarBloque(id, request);
  }

  @PostMapping("/files/{id}:commit")
  public CommitResult confirmar(@PathVariable String id, @RequestBody CommitRequest request) {
    return commit.confirmar(id, request);
  }

  @PostMapping("/files/{id}:keepalive")
  public KeepaliveResult mantenerActivo(@PathVariable String id) {
    return lifecycle.mantenerActivo(id);
  }

  @PostMapping("/files/{id}:close")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cerrar(@PathVariable String id) {
    lifecycle.cerrar(id);
  }
}
