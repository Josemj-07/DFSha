package co.edu.upb.dfsha.server.config;

import org.springframework.amqp.support.converter.*;
import org.springframework.context.annotation.*;

@Configuration
public class AmqpConfig {
  @Bean
  MessageConverter conversorMensajes() {
    var converter = new JacksonJsonMessageConverter();
    converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
    return converter;
  }
}
