package co.edu.upb.dfsha.node.adapter.crypto;

import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.InvalidTokenException;
import co.edu.upb.dfsha.node.domain.Op;
import co.edu.upb.dfsha.node.port.TokenVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;

import java.security.PublicKey;
import java.time.Clock;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifica los block tokens que emite el servidor: firma EdDSA, audiencia "dfsha-datanode"
 * y vencimiento. Solo después de esto el nodo confía en las operaciones y bloques del token.
 */
public class JwtTokenVerifier implements TokenVerifier {

    static final String AUDIENCIA = "dfsha-datanode";
    // Los relojes de las VM nunca coinciden del todo; sin margen, un token recién emitido podría fallar.
    private static final long MARGEN_RELOJ_SEGUNDOS = 30;

    private final JwtParser parser;

    public JwtTokenVerifier(PublicKey clavePublica, Clock clock) {
        this.parser = Jwts.parser()
                .verifyWith(clavePublica)
                .requireAudience(AUDIENCIA)
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds(MARGEN_RELOJ_SEGUNDOS)
                .build();
    }

    @Override
    public BlockGrant verificar(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("falta el block token");
        }
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            return construirPermiso(claims);
        } catch (JwtException | IllegalArgumentException | ClassCastException e) {
            // El mensaje nunca incluye el token: los tokens no se registran ni se devuelven.
            throw new InvalidTokenException("block token inválido");
        }
    }

    private static BlockGrant construirPermiso(Claims claims) {
        if (claims.getSubject() == null || claims.getExpiration() == null) {
            throw new IllegalArgumentException("faltan sub o exp");
        }
        return new BlockGrant(claims.getSubject(), leerOperaciones(claims), leerBloques(claims));
    }

    /** "ops" llega como "r" o "w"; se acepta también "rw" por si el servidor combina ambos. */
    private static Set<Op> leerOperaciones(Claims claims) {
        String ops = claims.get("ops", String.class);
        if (ops == null || ops.isEmpty()) {
            throw new IllegalArgumentException("faltan ops");
        }
        Set<Op> resultado = new HashSet<>();
        for (char codigo : ops.toCharArray()) {
            resultado.add(Op.interpretar(String.valueOf(codigo)));
        }
        return resultado;
    }

    private static Set<String> leerBloques(Claims claims) {
        List<?> bloques = claims.get("blk", List.class);
        if (bloques == null) {
            throw new IllegalArgumentException("falta blk");
        }
        Set<String> resultado = new HashSet<>();
        for (Object bloque : bloques) {
            resultado.add((String) bloque);
        }
        return resultado;
    }
}
