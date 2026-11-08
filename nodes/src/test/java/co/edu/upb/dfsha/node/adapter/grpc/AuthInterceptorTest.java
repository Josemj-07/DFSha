package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.InvalidTokenException;
import co.edu.upb.dfsha.node.domain.Op;
import co.edu.upb.dfsha.node.port.TokenVerifier;
import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthInterceptorTest {

    private static final ReadRequest LECTURA = ReadRequest.newBuilder().setBlockId("blk_01").setGen(1).build();

    // Doble del verificador real: solo "token-bueno" es válido.
    private final TokenVerifier tokens = token -> {
        if (!"token-bueno".equals(token)) {
            throw new InvalidTokenException("token inválido");
        }
        return new BlockGrant("jose", Set.of(Op.READ), Set.of("blk_01:1"));
    };

    private Server servidor;
    private ManagedChannel canal;

    @BeforeEach
    void arrancar() throws IOException {
        String nombre = InProcessServerBuilder.generateName();
        servidor = InProcessServerBuilder.forName(nombre).directExecutor()
                .addService(ServerInterceptors.intercept(new GrantEchoService(), new AuthInterceptor(tokens)))
                .build().start();
        canal = InProcessChannelBuilder.forName(nombre).directExecutor().build();
    }

    @AfterEach
    void detener() throws InterruptedException {
        canal.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        servidor.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void conTokenValidoElServicioRecibeElPermisoYElToken() {
        Chunk respuesta = leer("Bearer token-bueno");

        assertEquals("jose|token-bueno", respuesta.getData().toStringUtf8());
    }

    @Test
    void sinTokenRespondeUnauthenticated() {
        var error = assertThrows(StatusRuntimeException.class,
                () -> BlockServiceGrpc.newBlockingStub(canal).leerBloque(LECTURA).next());

        assertEquals(Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
    }

    @Test
    void conTokenInvalidoRespondeUnauthenticated() {
        var error = assertThrows(StatusRuntimeException.class, () -> leer("Bearer token-falso"));

        assertEquals(Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
    }

    @Test
    void exigeElPrefijoBearer() {
        var error = assertThrows(StatusRuntimeException.class, () -> leer("token-bueno"));

        assertEquals(Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
    }

    private Chunk leer(String authorization) {
        Metadata headers = new Metadata();
        headers.put(AuthInterceptor.AUTHORIZATION, authorization);
        return BlockServiceGrpc.newBlockingStub(canal)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .leerBloque(LECTURA)
                .next();
    }
}
