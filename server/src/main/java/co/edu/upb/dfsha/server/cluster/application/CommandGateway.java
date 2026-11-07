package co.edu.upb.dfsha.server.cluster.application;

import co.edu.upb.dfsha.server.cluster.domain.NodeCommand;

public interface CommandGateway {
  void enviar(String nodeId, NodeCommand command);
}
