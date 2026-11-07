package co.edu.upb.dfsha.server.cluster.application;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FailureDetector {
  private final ClusterState cluster;
  private final BrokerStatus broker;
  private final Clock clock;

  public FailureDetector(ClusterState cluster, BrokerStatus broker, Clock clock) {
    this.cluster = cluster;
    this.broker = broker;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 1000)
  public void revisar() {
    if (broker.estaConectado()) cluster.detectar(clock.instant());
  }
}
