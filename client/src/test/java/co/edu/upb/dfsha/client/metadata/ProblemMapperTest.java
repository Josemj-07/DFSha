package co.edu.upb.dfsha.client.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import co.edu.upb.dfsha.client.sdk.error.AuthException;
import co.edu.upb.dfsha.client.sdk.error.ConflictException;
import co.edu.upb.dfsha.client.sdk.error.DataIntegrityException;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import co.edu.upb.dfsha.client.sdk.error.NotFoundException;
import co.edu.upb.dfsha.client.sdk.error.QuotaException;
import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProblemMapperTest {

    private final ProblemMapper mapper = new ProblemMapper(new ObjectMapper());

    private DfsException mapear(int status, String code) {
        return mapper.mapear(status, "{\"code\":\"" + code + "\",\"detail\":\"d\",\"status\":" + status + "}");
    }

    @ParameterizedTest
    @CsvSource({
        "401, INVALID_CREDENTIALS, AuthException",
        "401, INVALID_TOKEN, AuthException",
        "429, TOO_MANY_ATTEMPTS, AuthException",
        "403, FORBIDDEN, AuthException",
        "404, NOT_FOUND, NotFoundException",
        "409, ALREADY_EXISTS, ConflictException",
        "409, DIR_NOT_EMPTY, ConflictException",
        "409, LOCK_CONFLICT, ConflictException",
        "409, VERSION_CONFLICT, ConflictException",
        "409, STALE_FENCING_TOKEN, ConflictException",
        "410, HANDLE_EXPIRED, ConflictException",
        "422, BLOCK_NOT_DURABLE, DataIntegrityException",
        "507, QUOTA_EXCEEDED, QuotaException",
        "503, INSUFFICIENT_NODES, UnavailableException",
        "503, SAFE_MODE, UnavailableException",
    })
    void cadaCodigoTieneSuExcepcion(int status, String code, String type) {
        DfsException error = mapear(status, code);
        assertEquals(type, error.getClass().getSimpleName());
        assertEquals(code, error.obtenerCodigo());
    }

    @Test
    void toleraRespuestasQueNoSonJson() {
        assertInstanceOf(UnavailableException.class, mapper.mapear(502, "<html>Bad Gateway</html>"));
        assertInstanceOf(InvalidOperationException.class, mapper.mapear(400, ""));
    }

    @Test
    void conservaElDetalleDeUnNoEncontrado() {
        assertInstanceOf(NotFoundException.class, mapear(404, "NOT_FOUND"));
        assertEquals("No existe: d", mapear(404, "NOT_FOUND").getMessage());
        assertInstanceOf(ConflictException.class, mapear(409, "CODIGO_NUEVO"));
        assertInstanceOf(AuthException.class, mapear(401, "CODIGO_NUEVO"));
        assertInstanceOf(DataIntegrityException.class, mapear(422, "CODIGO_NUEVO"));
        assertInstanceOf(QuotaException.class, mapear(507, "CODIGO_NUEVO"));
    }
}
