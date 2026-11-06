package co.edu.upb.dfsha.client.metadata;

import static co.edu.upb.dfsha.client.metadata.QueryString.codificar;

import java.util.List;
import java.util.Map;

/** Ciclo de vida de un archivo por REST. */
public final class RestFileSessionGateway implements FileSessionGateway {

    private record VersionResponse(long version) {
    }

    private final RestInvoker rest;

    public RestFileSessionGateway(RestInvoker rest) {
        this.rest = rest;
    }

    @Override
    public OpenResult abrir(OpenRequest request) {
        return rest.ejecutar(RestRequest.enviar("/files:open", request), OpenResult.class);
    }

    @Override
    public Allocation asignar(String handle, int firstIdx, int count) {
        var body = Map.of("firstIdx", firstIdx, "count", count);
        return rest.ejecutar(RestRequest.enviar(ruta(handle, "allocate"), body), Allocation.class);
    }

    @Override
    public Allocation recuperarBloque(String handle, int idx, List<String> failedNodes) {
        var body = Map.of("idx", idx, "failedNodes", failedNodes);
        return rest.ejecutar(RestRequest.enviar(ruta(handle, "recover-block"), body), Allocation.class);
    }

    @Override
    public long confirmar(String handle, CommitRequest request) {
        return rest.ejecutar(RestRequest.enviarIdempotente(ruta(handle, "commit"), request),
                VersionResponse.class).version();
    }

    @Override
    public KeepaliveResult mantenerActivo(String handle) {
        return rest.ejecutar(RestRequest.enviarIdempotente(ruta(handle, "keepalive"), null), KeepaliveResult.class);
    }

    @Override
    public void cerrar(String handle) {
        rest.ejecutarSinRespuesta(RestRequest.enviarIdempotente(ruta(handle, "close"), null));
    }

    @Override
    public void reportarReplica(ReplicaReport report) {
        rest.ejecutarSinRespuesta(RestRequest.enviar("/reports/replica", report));
    }

    private String ruta(String handle, String action) {
        return "/files/" + codificar(handle) + ":" + action;
    }
}
