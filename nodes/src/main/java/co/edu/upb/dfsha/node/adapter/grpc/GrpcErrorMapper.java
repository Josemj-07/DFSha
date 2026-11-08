package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.domain.BlockAlreadyExistsException;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.domain.BlockWriteInProgressException;
import co.edu.upb.dfsha.node.domain.DataIntegrityException;
import co.edu.upb.dfsha.node.domain.InsufficientSpaceException;
import co.edu.upb.dfsha.node.domain.InvalidTokenException;
import co.edu.upb.dfsha.node.domain.PermissionDeniedException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Único lugar donde las excepciones del dominio se convierten en códigos gRPC, según la tabla
 * de la sección 4.1 del README. Así todos los servicios responden igual ante el mismo error.
 */
public final class GrpcErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(GrpcErrorMapper.class);

    private GrpcErrorMapper() {
    }

    public static StatusRuntimeException traducir(Throwable error) {
        Status status = switch (error) {
            case StatusRuntimeException yaTraducido -> yaTraducido.getStatus();
            case BlockAlreadyExistsException e -> Status.ALREADY_EXISTS.withDescription(e.getMessage());
            case BlockNotFoundException e -> Status.NOT_FOUND.withDescription(e.getMessage());
            case BlockWriteInProgressException e -> Status.ABORTED.withDescription(e.getMessage());
            case DataIntegrityException e -> Status.DATA_LOSS.withDescription(e.getMessage());
            case InsufficientSpaceException e -> Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage());
            case InvalidTokenException e -> Status.UNAUTHENTICATED.withDescription("token inválido");
            case PermissionDeniedException e -> Status.PERMISSION_DENIED.withDescription(e.getMessage());
            case IllegalArgumentException e -> Status.INVALID_ARGUMENT.withDescription(e.getMessage());
            default -> registrarInesperado(error);
        };
        return status.asRuntimeException();
    }

    // Un error no previsto no se describe al cliente: podría revelar rutas o detalles internos.
    private static Status registrarInesperado(Throwable error) {
        log.error("Error inesperado en una llamada gRPC", error);
        return Status.INTERNAL.withDescription("error interno del nodo");
    }
}
