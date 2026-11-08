package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.InvalidTokenException;
import co.edu.upb.dfsha.node.port.TokenVerifier;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * Se ejecuta antes de cada llamada gRPC, en los dos puertos. Si el block token no es válido,
 * corta la llamada antes de que llegue al servicio; si lo es, deja el permiso en el Context
 * para que el servicio compruebe el bloque y la operación concretos.
 */
public class AuthInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String PREFIJO = "Bearer ";

    /** Permiso ya verificado de la llamada en curso. */
    public static final Context.Key<BlockGrant> GRANT = Context.key("dfs-grant");
    /** Token sin modificar: en la cadena se reenvía tal cual al siguiente nodo. */
    public static final Context.Key<String> TOKEN = Context.key("dfs-token");

    private final TokenVerifier tokens;

    public AuthInterceptor(TokenVerifier tokens) {
        this.tokens = tokens;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        try {
            String token = extraerToken(headers);
            BlockGrant grant = tokens.verificar(token);
            Context contexto = Context.current().withValues(GRANT, grant, TOKEN, token);
            return Contexts.interceptCall(contexto, call, headers, next);
        } catch (InvalidTokenException e) {
            call.close(Status.UNAUTHENTICATED.withDescription("token inválido"), new Metadata());
            return new ServerCall.Listener<>() { };
        }
    }

    private static String extraerToken(Metadata headers) {
        String valor = headers.get(AUTHORIZATION);
        if (valor == null || !valor.startsWith(PREFIJO)) {
            throw new InvalidTokenException("falta el block token");
        }
        return valor.substring(PREFIJO.length());
    }
}
