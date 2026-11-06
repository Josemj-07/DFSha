package co.edu.upb.dfsha.client.data;

import io.grpc.Status;
import java.util.List;
import java.util.Set;

/** Un nodo de la cadena no completó la escritura. */
public final class ReplicaWriteException extends RuntimeException {

    private static final Set<Status.Code> RECOVERABLE = Set.of(Status.Code.ABORTED, Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED, Status.Code.CANCELLED, Status.Code.DATA_LOSS,
            Status.Code.RESOURCE_EXHAUSTED, Status.Code.UNAUTHENTICATED);

    private final Status.Code code;
    private final List<String> failedNodes;

    public ReplicaWriteException(Status.Code code, List<String> failedNodes, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.failedNodes = List.copyOf(failedNodes);
    }

    public boolean esRecuperable() {
        return RECOVERABLE.contains(code);
    }

    public List<String> obtenerNodosFallidos() {
        return failedNodes;
    }
}
