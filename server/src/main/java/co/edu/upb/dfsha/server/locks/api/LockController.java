package co.edu.upb.dfsha.server.locks.api;

import co.edu.upb.dfsha.server.auth.application.CurrentUser;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.locks.application.LockManager;
import co.edu.upb.dfsha.server.locks.domain.*;
import co.edu.upb.dfsha.server.namespace.application.*;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/locks")
public class LockController {
  private final LockManager locks;
  private final AccessPolicy policy;
  private final CurrentUser current;
  private final MetadataStore store;

  public LockController(
      LockManager locks, AccessPolicy policy, CurrentUser current, MetadataStore store) {
    this.locks = locks;
    this.policy = policy;
    this.current = current;
    this.store = store;
  }

  public record Request(String path, String mode, BlockRange range, long ttlMs, long waitMs) {}

  public record Result(String lockId, String fencingToken, Instant expiresAt) {}

  @PostMapping
  public Result adquirir(@RequestBody Request request) {
    String path = policy.verificar(request.path());
    long inode =
        store.leer(
            s -> {
              var i = NamespaceService.obtener(s, path);
              policy.verificar(i, true);
              return i.id();
            });
    var lock =
        locks.adquirir(
            inode,
            current.obtenerNombre(),
            request.mode(),
            request.range() == null ? BlockRange.completo() : request.range(),
            request.ttlMs(),
            request.waitMs());
    return new Result(lock.id(), lock.fencingToken(), lock.expiresAt());
  }

  @PostMapping("/{id}:renew")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void renovar(@PathVariable String id) {
    locks.renovar(id, current.obtenerNombre());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void liberar(@PathVariable String id) {
    locks.liberar(id, current.obtenerNombre());
  }
}
