package co.edu.upb.dfsha.server.support;

import java.time.*;

public class MutableClock extends Clock {
  private Instant now = Instant.parse("2026-11-06T12:00:00Z");

  public void avanzar(long seconds) {
    now = now.plusSeconds(seconds);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
