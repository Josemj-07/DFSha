package co.edu.upb.dfsha.server.auth.application;

import co.edu.upb.dfsha.server.common.DfsException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class LoginThrottle {
  private final Clock clock;
  private final Map<String, Deque<Instant>> failures = new HashMap<>();

  public LoginThrottle(Clock clock) {
    this.clock = clock;
  }

  private Deque<Instant> obtener(String key) {
    var queue = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
    while (!queue.isEmpty() && !queue.peek().isAfter(clock.instant().minusSeconds(900)))
      queue.remove();
    return queue;
  }

  public synchronized void verificar(String user, String ip) {
    DfsException.exigir(
        obtener("u:" + user).size() < 5 && obtener("i:" + ip).size() < 10,
        429,
        "TOO_MANY_ATTEMPTS",
        "Demasiados intentos");
  }

  public synchronized void registrarFallo(String user, String ip) {
    obtener("u:" + user).add(clock.instant());
    obtener("i:" + ip).add(clock.instant());
  }
}
