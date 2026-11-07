package co.edu.upb.dfsha.server.journal.application;

import co.edu.upb.dfsha.server.journal.domain.*;
import co.edu.upb.dfsha.server.journal.op.JournalOps;
import java.util.function.*;

public interface MetadataStore {
  <T> T leer(Function<MetadataState, T> query);

  <T> T escribir(Function<MetadataState, Mutation<T>> decide);

  default <T> T mutar(Function<MetadataState, T> change) {
    return escribir("metadata-change", change);
  }

  default <T> T escribir(String type, Function<MetadataState, T> change) {
    return escribir(
        before -> {
          MetadataState candidate = before.copiar();
          T result = change.apply(candidate);
          return new Mutation<>(
              type, JournalOps.crear(type, StateChange.entre(before, candidate)), result);
        });
  }

  void renovar(Consumer<MetadataState> change);
}
