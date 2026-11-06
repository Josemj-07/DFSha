package co.edu.upb.dfsha.client.metadata;

import co.edu.upb.dfsha.client.sdk.model.LockHandle;

/** Operaciones sobre locks. */
public interface LockGateway {

    LockHandle bloquear(LockRequest request);

    void renovarBloqueo(String lockId);

    void desbloquear(String lockId);
}
