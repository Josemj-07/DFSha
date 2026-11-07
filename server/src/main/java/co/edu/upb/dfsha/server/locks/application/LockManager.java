package co.edu.upb.dfsha.server.locks.application;

import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import co.edu.upb.dfsha.server.locks.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class LockManager {
  private final MetadataStore store;
  private final Clock clock;
  private final LockWaitQueue waiters = new LockWaitQueue();

  public LockManager(MetadataStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public FileLock conceder(
      MetadataState s, long inode, String owner, String mode, BlockRange range, long ttl) {
    return conceder(s, inode, owner, mode, range, ttl, null);
  }

  private FileLock conceder(
      MetadataState s,
      long inode,
      String owner,
      String mode,
      BlockRange range,
      long ttl,
      LockWaiter waiter) {
    DfsException.exigir(
        waiters.permitir(inode, waiter),
        409,
        "LOCK_CONFLICT",
        "Hay solicitudes anteriores en cola");
    DfsException.exigir(
        Set.of("SHARED", "EXCLUSIVE").contains(mode),
        400,
        "INVALID_REQUEST",
        "Modo de lock invalido");
    for (FileLock held : s.locks.values())
      if (held.inodeId() == inode
          && held.expiresAt().isAfter(clock.instant())
          && held.range().solapar(range))
        DfsException.exigir(
            !mode.equals("EXCLUSIVE") && !held.mode().equals("EXCLUSIVE"),
            409,
            "LOCK_CONFLICT",
            "Rango ocupado");
    String id = UUID.randomUUID().toString();
    long duration = ttl == 0 ? 30000 : ttl;
    DfsException.exigir(
        duration > 0 && duration <= 30000, 400, "INVALID_REQUEST", "TTL fuera de rango");
    FileLock result =
        new FileLock(
            id,
            owner,
            inode,
            mode,
            range,
            Long.toString(++s.fencingSequence),
            clock.instant().plusMillis(duration),
            duration);
    s.locks.put(id, result);
    return result;
  }

  public FileLock adquirir(
      long inode, String owner, String mode, BlockRange range, long ttl, long waitMs) {
    DfsException.exigir(
        waitMs >= 0 && waitMs <= 300000, 400, "INVALID_REQUEST", "Espera fuera de rango");
    LockWaiter waiter = waiters.anotar(inode);
    long deadline = System.nanoTime() + waitMs * 1000000;
    try {
      while (true) {
        var ready = waiter.preparar();
        try {
          return store.escribir(
              "acquire-lock", s -> conceder(s, inode, owner, mode, range, ttl, waiter));
        } catch (DfsException ex) {
          if (!ex.obtenerCodigo().equals("LOCK_CONFLICT")) throw ex;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new DfsException(409, "LOCK_CONFLICT", "Rango ocupado");
        esperar(ready, remaining);
      }
    } finally {
      waiters.retirar(inode, waiter);
    }
  }

  private void esperar(java.util.concurrent.CompletableFuture<Void> ready, long nanos) {
    try {
      ready.get(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    } catch (java.util.concurrent.TimeoutException ex) {
      /* Se comprueba el lock una ultima vez. */
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new DfsException(409, "LOCK_CONFLICT", "Espera interrumpida");
    } catch (java.util.concurrent.ExecutionException ex) {
      throw new IllegalStateException("Fallo al despertar la cola de locks", ex);
    }
  }

  public void verificar(MetadataState s, String token, long inode, String owner, BlockRange range) {
    DfsException.exigir(
        s.locks.values().stream()
            .anyMatch(
                l ->
                    l.inodeId() == inode
                        && l.owner().equals(owner)
                        && l.mode().equals("EXCLUSIVE")
                        && l.fencingToken().equals(token)
                        && l.expiresAt().isAfter(clock.instant())
                        && l.range().contener(range)),
        409,
        "STALE_FENCING_TOKEN",
        "Lock vencido o insuficiente");
  }

  public void renovar(String id, String owner) {
    store.renovar(
        s -> {
          FileLock l = obtener(s, id, owner);
          s.locks.put(
              id,
              new FileLock(
                  l.id(),
                  owner,
                  l.inodeId(),
                  l.mode(),
                  l.range(),
                  l.fencingToken(),
                  clock.instant().plusMillis(l.ttlMs()),
                  l.ttlMs()));
        });
  }

  public void liberar(String id, String owner) {
    long inode =
        store.escribir(
            "release-lock",
            s -> {
              var held = obtener(s, id, owner);
              s.locks.remove(id);
              return held.inodeId();
            });
    waiters.despertar(inode);
  }

  private FileLock obtener(MetadataState s, String id, String owner) {
    FileLock l = s.locks.get(id);
    DfsException.exigir(
        l != null && l.owner().equals(owner) && l.expiresAt().isAfter(clock.instant()),
        409,
        "LOCK_CONFLICT",
        "Lock inexistente o vencido");
    return l;
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
  public void limpiar() {
    boolean expired =
        store.leer(
            s -> s.locks.values().stream().anyMatch(l -> !l.expiresAt().isAfter(clock.instant())));
    if (expired) {
      store.escribir(
          "release-lock",
          s -> {
            s.locks.values().removeIf(l -> !l.expiresAt().isAfter(clock.instant()));
            return null;
          });
    }
    waiters.despertarTodos();
  }
}
