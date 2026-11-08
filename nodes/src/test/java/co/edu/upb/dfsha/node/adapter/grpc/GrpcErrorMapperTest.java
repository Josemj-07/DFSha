package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.domain.BlockAlreadyExistsException;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.domain.BlockWriteInProgressException;
import co.edu.upb.dfsha.node.domain.DataIntegrityException;
import co.edu.upb.dfsha.node.domain.InsufficientSpaceException;
import co.edu.upb.dfsha.node.domain.InvalidTokenException;
import co.edu.upb.dfsha.node.domain.PermissionDeniedException;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcErrorMapperTest {

    @Test
    void traduceCadaErrorDelDominioAlCodigoDelContrato() {
        assertEquals(Status.Code.ALREADY_EXISTS, codigo(new BlockAlreadyExistsException("x")));
        assertEquals(Status.Code.NOT_FOUND, codigo(new BlockNotFoundException("x")));
        assertEquals(Status.Code.ABORTED, codigo(new BlockWriteInProgressException("x")));
        assertEquals(Status.Code.DATA_LOSS, codigo(new DataIntegrityException("x", 0)));
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, codigo(new InsufficientSpaceException("x")));
        assertEquals(Status.Code.UNAUTHENTICATED, codigo(new InvalidTokenException("x")));
        assertEquals(Status.Code.PERMISSION_DENIED, codigo(new PermissionDeniedException("x")));
        assertEquals(Status.Code.INVALID_ARGUMENT, codigo(new IllegalArgumentException("x")));
    }

    @Test
    void dataLossIncluyeElOffset() {
        String descripcion = GrpcErrorMapper.traducir(new DataIntegrityException("CRC incorrecto", 1048576))
                .getStatus().getDescription();

        assertTrue(descripcion.contains("1048576"));
    }

    @Test
    void unErrorInesperadoNoRevelaDetalles() {
        var status = GrpcErrorMapper.traducir(new IllegalStateException("/data/blocks/secreto")).getStatus();

        assertEquals(Status.Code.INTERNAL, status.getCode());
        assertFalse(status.getDescription().contains("/data"));
    }

    @Test
    void respetaUnStatusYaTraducido() {
        assertEquals(Status.Code.ABORTED, codigo(Status.ABORTED.asRuntimeException()));
    }

    private static Status.Code codigo(Throwable error) {
        return GrpcErrorMapper.traducir(error).getStatus().getCode();
    }
}
