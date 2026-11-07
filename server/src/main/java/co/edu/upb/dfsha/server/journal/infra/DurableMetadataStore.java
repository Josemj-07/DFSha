package co.edu.upb.dfsha.server.journal.infra;

import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.config.DfsProperties;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.journal.domain.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DurableMetadataStore implements MetadataStore {
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  private final FileEditLog log;
  private MetadataState state;
  private long entries;
  private final co.edu.upb.dfsha.server.journal.application.MutationAuditor auditor;
  private final java.time.Clock clock;

  public DurableMetadataStore(
      DfsProperties properties,
      JsonMapper mapper,
      java.time.Clock clock,
      co.edu.upb.dfsha.server.journal.application.MutationAuditor auditor)
      throws IOException {

    this.auditor = auditor;
    this.clock = clock;
    log = new FileEditLog(properties.dataDir().resolve("journal"), mapper);
    state = log.recuperar();
  }

  private MetadataState copiar() {
    return state.copiar();
  }

  @Override
  public <T> T leer(Function<MetadataState, T> query) {
    lock.readLock().lock();
    try {
      return query.apply(copiar());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public <T> T escribir(
      Function<MetadataState, co.edu.upb.dfsha.server.journal.application.Mutation<T>> decide) {
    lock.writeLock().lock();
    try {
      var mutation = decide.apply(copiar());
      MetadataState candidate = copiar();
      mutation.operation().aplicar(candidate);
      auditor.registrar(state, candidate);
      ReferenceCounter.actualizar(candidate, clock.instant());
      co.edu.upb.dfsha.server.audit.domain.UsageAccounting.actualizar(candidate);
      StateChange op = StateChange.entre(state, candidate);
      log.anexar(co.edu.upb.dfsha.server.journal.op.JournalOps.crear(mutation.type(), op));
      op.aplicar(state);
      if (++entries >= 100000) {
        log.guardarSnapshot(state);
        entries = 0;
      }
      return mutation.result();
    } catch (IOException ex) {
      throw new DfsException(503, "STORAGE_UNAVAILABLE", "No se pudo persistir el cambio");
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void renovar(Consumer<MetadataState> change) {
    lock.writeLock().lock();
    try {
      change.accept(state);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Scheduled(fixedDelay = 600000)
  public void guardarSnapshot() throws IOException {
    lock.readLock().lock();
    try {
      log.guardarSnapshot(state);
      entries = 0;
    } finally {
      lock.readLock().unlock();
    }
  }

  @PreDestroy
  public void cerrar() throws IOException {
    log.close();
  }
}
