package co.edu.upb.dfsha.server.support;

import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.journal.application.Mutation;
import co.edu.upb.dfsha.server.journal.domain.MetadataState;
import java.util.function.*;

public class MemoryStore implements MetadataStore {
  private MetadataState state = new MetadataState();
  private final java.time.Clock clock;

  public MemoryStore(java.time.Clock clock) {
    this.clock = clock;
  }

  private MetadataState copiar() {
    return state.copiar();
  }

  @Override
  public synchronized <T> T leer(Function<MetadataState, T> query) {
    return query.apply(copiar());
  }

  @Override
  public synchronized <T> T escribir(Function<MetadataState, Mutation<T>> decide) {
    var next = copiar();
    var mutation = decide.apply(copiar());
    mutation.operation().aplicar(next);
    co.edu.upb.dfsha.server.journal.domain.ReferenceCounter.actualizar(next, clock.instant());
    co.edu.upb.dfsha.server.audit.domain.UsageAccounting.actualizar(next);
    state = next;
    return mutation.result();
  }

  @Override
  public synchronized void renovar(Consumer<MetadataState> change) {
    change.accept(state);
  }
}
