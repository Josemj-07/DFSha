package co.edu.upb.dfsha.server.journal.op;

import co.edu.upb.dfsha.server.journal.domain.*;

public record ReleaseLock(StateChange change) implements JournalOp {
  @Override
  public void aplicar(MetadataState state) {
    change.aplicar(state);
  }
}
