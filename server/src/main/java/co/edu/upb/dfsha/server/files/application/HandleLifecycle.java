package co.edu.upb.dfsha.server.files.application;

import co.edu.upb.dfsha.server.auth.application.TokenIssuer;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import co.edu.upb.dfsha.server.locks.domain.FileLock;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class HandleLifecycle {
  private final MetadataStore store;
  private final HandleAccess access;
  private final TokenIssuer tokens;
  private final Clock clock;

  public HandleLifecycle(
      MetadataStore store, HandleAccess access, TokenIssuer tokens, Clock clock) {
    this.store = store;
    this.access = access;
    this.tokens = tokens;
    this.clock = clock;
  }

  @PostConstruct
  public void restaurarLeases() {
    store.renovar(
        s -> {
          s.handles.replaceAll((id, h) -> extender(h));
          s.locks.replaceAll(
              (id, l) ->
                  new FileLock(
                      id,
                      l.owner(),
                      l.inodeId(),
                      l.mode(),
                      l.range(),
                      l.fencingToken(),
                      clock.instant().plusMillis(l.ttlMs()),
                      l.ttlMs()));
        });
  }

  public KeepaliveResult mantenerActivo(String id) {
    Handle h = store.leer(s -> access.obtener(s, id));
    store.renovar(
        actual -> {
          Handle active = access.obtener(actual, id);
          actual.handles.put(id, extender(active));
          if (active.lockId() != null) {
            var l = actual.locks.get(active.lockId());
            if (l != null && l.expiresAt().isAfter(clock.instant()))
              actual.locks.put(
                  l.id(),
                  new FileLock(
                      l.id(),
                      l.owner(),
                      l.inodeId(),
                      l.mode(),
                      l.range(),
                      l.fencingToken(),
                      clock.instant().plusMillis(l.ttlMs()),
                      l.ttlMs()));
          }
        });
    return new KeepaliveResult(
        30000,
        h.mode().equals("w")
            ? null
            : tokens.emitirBloques(
                h.owner(),
                "r",
                h.baseBlocks().stream().map(b -> b.blockId() + ":" + b.gen()).toList()));
  }

  public void cerrar(String id) {
    store.escribir(
        "close-handle",
        s -> {
          Handle h = access.obtener(s, id);
          eliminar(s, h);
          return null;
        });
  }

  private Handle extender(Handle h) {
    return new Handle(
        h.id(),
        h.owner(),
        h.inodeId(),
        h.mode(),
        h.baseVersion(),
        h.baseBlocks(),
        h.lockId(),
        clock.instant().plusSeconds(30),
        h.pending(),
        h.lastCommit(),
        h.lastVersion());
  }

  private void eliminar(MetadataState s, Handle h) {
    s.handles.remove(h.id());
    if (h.lockId() != null) s.locks.remove(h.lockId());
    if (!s.directories.contenerInodo(h.inodeId())
        && s.handles.values().stream().noneMatch(other -> other.inodeId() == h.inodeId()))
      s.inodes.remove(h.inodeId());
  }

  @Scheduled(fixedDelay = 1000)
  public void limpiar() {
    if (store.leer(
        s -> s.handles.values().stream().anyMatch(h -> !h.expiresAt().isAfter(clock.instant()))))
      store.mutar(
          s -> {
            s.handles.values().stream()
                .filter(h -> !h.expiresAt().isAfter(clock.instant()))
                .toList()
                .forEach(h -> eliminar(s, h));
            return null;
          });
  }
}
