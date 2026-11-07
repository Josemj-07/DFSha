package co.edu.upb.dfsha.server.cluster.infra;

import co.edu.upb.dfsha.server.cluster.application.CommandGateway;
import co.edu.upb.dfsha.server.cluster.domain.NodeCommand;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class AmqpCommandGateway implements CommandGateway {
  private final RabbitTemplate template;

  public AmqpCommandGateway(RabbitTemplate template) {
    this.template = template;
    template.setMandatory(true);
  }

  @Override
  public void enviar(String nodeId, NodeCommand command) {
    var correlation = new CorrelationData(command.commandId());
    template.convertAndSend("dfs.commands", nodeId, command, correlation);
    try {
      var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
      if (!confirm.ack() || correlation.getReturned() != null)
        throw new IllegalStateException("RabbitMQ no acepto el comando");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Envio interrumpido", ex);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
      throw new IllegalStateException("No se confirmo el comando", ex);
    }
  }
}
