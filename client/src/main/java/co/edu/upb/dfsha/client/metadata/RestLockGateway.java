package co.edu.upb.dfsha.client.metadata;

import static co.edu.upb.dfsha.client.metadata.QueryString.codificar;

import co.edu.upb.dfsha.client.sdk.model.LockHandle;
import co.edu.upb.dfsha.client.sdk.model.LockMode;
import java.time.Duration;

/** Locks por REST. */
public final class RestLockGateway implements LockGateway {

    private static final Duration RESPONSE_MARGIN = Duration.ofSeconds(10);

    private record LockResponse(String lockId, String fencingToken, String expiresAt) {
    }

    private final RestInvoker rest;

    public RestLockGateway(RestInvoker rest) {
        this.rest = rest;
    }

    /** El tiempo límite incluye {@code waitMs}, porque el servidor retiene la petición en su cola. */
    @Override
    public LockHandle bloquear(LockRequest request) {
        Duration timeout = RESPONSE_MARGIN.plusMillis(request.waitMs());
        LockResponse response = rest.ejecutar(
                RestRequest.enviar("/locks", request).conTiempoLimite(timeout), LockResponse.class);
        return new LockHandle(response.lockId(), response.fencingToken(), response.expiresAt(),
                LockMode.valueOf(request.mode()), request.range());
    }

    @Override
    public void renovarBloqueo(String lockId) {
        rest.ejecutarSinRespuesta(RestRequest.enviarIdempotente("/locks/" + codificar(lockId) + ":renew", null));
    }

    @Override
    public void desbloquear(String lockId) {
        rest.ejecutarSinRespuesta(RestRequest.borrar("/locks/" + codificar(lockId)));
    }
}
