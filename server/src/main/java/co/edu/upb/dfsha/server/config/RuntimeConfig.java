package co.edu.upb.dfsha.server.config;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DfsProperties.class)
public class RuntimeConfig {
  @Bean
  Clock reloj() {
    return Clock.systemUTC();
  }

  @Bean
  SecureRandom aleatoriedad() {
    return new SecureRandom();
  }
}
