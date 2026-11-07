package co.edu.upb.dfsha.server.journal.application;

import co.edu.upb.dfsha.server.journal.domain.MetadataState;

public interface MutationAuditor {
  void registrar(MetadataState before, MetadataState after);
}
