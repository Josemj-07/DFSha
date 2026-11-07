package co.edu.upb.dfsha.server.auth.infra;

import co.edu.upb.dfsha.server.config.DfsProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class TokenService implements co.edu.upb.dfsha.server.auth.application.TokenIssuer {
  private final PrivateKey accessKey, blockKey;
  private final PublicKey accessPublic;
  private final Clock clock;

  public TokenService(DfsProperties props, Clock clock) throws Exception {
    this.clock = clock;
    var dir = props.tlsDir().resolve("keys");
    accessKey = PemKeyLoader.cargarPrivada(dir.resolve("jwt.key"));
    blockKey = PemKeyLoader.cargarPrivada(dir.resolve("blocktoken.key"));
    accessPublic = PemKeyLoader.cargarPublica(dir.resolve("jwt.pub"));
  }

  public String emitirAcceso(String user, String role) {
    return Jwts.builder()
        .subject(user)
        .claim("role", role)
        .id(UUID.randomUUID().toString())
        .issuedAt(Date.from(clock.instant()))
        .expiration(Date.from(clock.instant().plusSeconds(900)))
        .signWith(accessKey, Jwts.SIG.EdDSA)
        .compact();
  }

  public Claims verificar(String token) {
    return Jwts.parser()
        .verifyWith(accessPublic)
        .clock(() -> Date.from(clock.instant()))
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }

  public String emitirBloques(String user, String ops, List<String> blocks) {
    return Jwts.builder()
        .subject(user)
        .audience()
        .add("dfsha-datanode")
        .and()
        .claim("ops", ops)
        .claim("blk", blocks)
        .expiration(Date.from(clock.instant().plusSeconds(600)))
        .signWith(blockKey, Jwts.SIG.EdDSA)
        .compact();
  }
}
