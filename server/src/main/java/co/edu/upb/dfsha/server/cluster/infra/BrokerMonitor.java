package co.edu.upb.dfsha.server.cluster.infra;

import co.edu.upb.dfsha.server.cluster.application.ClusterState;
import java.time.Clock;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.stereotype.Component;

@Component
public class BrokerMonitor
    implements ConnectionListener, co.edu.upb.dfsha.server.cluster.application.BrokerStatus {
  private volatile boolean connected;
  private final ClusterState cluster;
  private final Clock clock;

  public BrokerMonitor(ClusterState cluster, Clock clock, CachingConnectionFactory factory) {
    this.cluster = cluster;
    this.clock = clock;
    factory.addConnectionListener(this);
  }

  @Override
  public void onCreate(Connection connection) {
    cluster.reiniciarUltimoContacto(clock.instant());
    connected = true;
  }

  @Override
  public void onClose(Connection connection) {
    connected = false;
  }

  @Override
  public void onShutDown(com.rabbitmq.client.ShutdownSignalException signal) {
    connected = false;
  }

  public boolean estaConectado() {
    return connected;
  }
}
