package co.edu.upb.dfsha.server.auth.application;

import java.util.List;

public interface TokenIssuer {
  String emitirAcceso(String user, String role);

  String emitirBloques(String user, String ops, List<String> blocks);
}
