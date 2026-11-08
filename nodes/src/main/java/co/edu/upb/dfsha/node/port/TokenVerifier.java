package co.edu.upb.dfsha.node.port;

import co.edu.upb.dfsha.node.domain.BlockGrant;

/** Verifica un block token firmado por el servidor. */
public interface TokenVerifier {

    /** Lanza InvalidTokenException si el token no es válido. */
    BlockGrant verificar(String token);
}
