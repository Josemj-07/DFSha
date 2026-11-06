package co.edu.upb.dfsha.client.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocationCacheTest {

    private static final class MovableClock extends Clock {
        private Instant now = Instant.EPOCH;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final LocationCache cache = new LocationCache(clock);

    @Test
    void caducaALos60Segundos() {
        cache.guardar("/a", 1, List.of());
        clock.now = clock.now.plus(Duration.ofSeconds(59));
        assertTrue(cache.obtener("/a", 1).isPresent());
        clock.now = clock.now.plus(Duration.ofSeconds(1));
        assertFalse(cache.obtener("/a", 1).isPresent());
    }

    @Test
    void distingueVersionesEInvalida() {
        cache.guardar("/a", 1, List.of());
        assertFalse(cache.obtener("/a", 2).isPresent());
        cache.invalidar("/a", 1);
        assertFalse(cache.obtener("/a", 1).isPresent());
    }
}
