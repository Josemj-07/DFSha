package co.edu.upb.dfsha.node.adapter.crypto;

import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.InvalidTokenException;
import co.edu.upb.dfsha.node.domain.Op;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenVerifierTest {

    private static final Instant AHORA = Instant.parse("2026-11-07T12:00:00Z");
    private static final BlockKey BLOQUE = new BlockKey("blk_01", 1);

    private final KeyPair claveServidor = TestKeys.generarEd25519();
    private final JwtTokenVerifier verifier =
            new JwtTokenVerifier(claveServidor.getPublic(), Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void aceptaUnTokenComoLosQueEmiteElServidor() {
        BlockGrant permiso = verifier.verificar(emitir(claveServidor.getPrivate(), "w", JwtTokenVerifier.AUDIENCIA,
                AHORA.plus(Duration.ofMinutes(10))));

        assertEquals("jose", permiso.subject());
        assertTrue(permiso.permite(Op.WRITE, BLOQUE));
    }

    @Test
    void unTokenDeLecturaNoPermiteEscribir() {
        BlockGrant permiso = verifier.verificar(emitirValido("r"));

        assertTrue(permiso.permite(Op.READ, BLOQUE));
        assertFalse(permiso.permite(Op.WRITE, BLOQUE));
    }

    @Test
    void noPermiteOtroBloqueNiOtraGeneracion() {
        BlockGrant permiso = verifier.verificar(emitirValido("w"));

        assertFalse(permiso.permite(Op.WRITE, new BlockKey("blk_02", 1)));
        assertFalse(permiso.permite(Op.WRITE, new BlockKey("blk_01", 2)));
    }

    @Test
    void rechazaUnTokenVencido() {
        String token = emitir(claveServidor.getPrivate(), "r", JwtTokenVerifier.AUDIENCIA,
                AHORA.minus(Duration.ofMinutes(5)));

        assertThrows(InvalidTokenException.class, () -> verifier.verificar(token));
    }

    @Test
    void rechazaUnTokenFirmadoPorOtraClave() {
        String token = emitir(TestKeys.generarEd25519().getPrivate(), "r", JwtTokenVerifier.AUDIENCIA,
                AHORA.plus(Duration.ofMinutes(10)));

        assertThrows(InvalidTokenException.class, () -> verifier.verificar(token));
    }

    @Test
    void rechazaUnTokenParaOtraAudiencia() {
        String token = emitir(claveServidor.getPrivate(), "r", "otro-servicio", AHORA.plus(Duration.ofMinutes(10)));

        assertThrows(InvalidTokenException.class, () -> verifier.verificar(token));
    }

    @Test
    void rechazaUnTokenSinBloques() {
        String token = Jwts.builder().subject("jose").audience().add(JwtTokenVerifier.AUDIENCIA).and()
                .claim("ops", "r").expiration(Date.from(AHORA.plus(Duration.ofMinutes(10))))
                .signWith(claveServidor.getPrivate(), Jwts.SIG.EdDSA).compact();

        assertThrows(InvalidTokenException.class, () -> verifier.verificar(token));
    }

    @Test
    void rechazaTokensVaciosOMalFormados() {
        assertThrows(InvalidTokenException.class, () -> verifier.verificar(null));
        assertThrows(InvalidTokenException.class, () -> verifier.verificar(""));
        assertThrows(InvalidTokenException.class, () -> verifier.verificar("esto.no.es-un-jwt"));
    }

    @Test
    void elMensajeDeErrorNoIncluyeElToken() {
        String token = emitir(TestKeys.generarEd25519().getPrivate(), "r", JwtTokenVerifier.AUDIENCIA,
                AHORA.plus(Duration.ofMinutes(10)));

        var error = assertThrows(InvalidTokenException.class, () -> verifier.verificar(token));

        assertFalse(error.getMessage().contains(token));
    }

    private String emitirValido(String ops) {
        return emitir(claveServidor.getPrivate(), ops, JwtTokenVerifier.AUDIENCIA, AHORA.plus(Duration.ofMinutes(10)));
    }

    // Igual que TokenService.emitirBloques del servidor.
    private static String emitir(PrivateKey clave, String ops, String audiencia, Instant vence) {
        return Jwts.builder()
                .subject("jose")
                .audience().add(audiencia).and()
                .claim("ops", ops)
                .claim("blk", List.of("blk_01:1"))
                .expiration(Date.from(vence))
                .signWith(clave, Jwts.SIG.EdDSA)
                .compact();
    }
}
