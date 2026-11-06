package co.edu.upb.dfsha.client.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final RetryPolicy policy = new RetryPolicy(
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps::add);

    private String fallar(AtomicInteger calls, String code, int failures) {
        if (calls.incrementAndGet() <= failures) {
            throw new UnavailableException(code, "falla");
        }
        return "ok";
    }

    @Test
    void reintentaModoSeguroAunqueNoSeaIdempotente() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("ok", policy.ejecutar(() -> fallar(calls, "SAFE_MODE", 2), false));
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps);
    }

    @Test
    void reintentaFalloDeConexionSoloSiEsIdempotente() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("ok", policy.ejecutar(() -> fallar(calls, RetryPolicy.SERVER_UNREACHABLE, 1), true));
        AtomicInteger once = new AtomicInteger();
        assertThrows(UnavailableException.class,
                () -> policy.ejecutar(() -> fallar(once, RetryPolicy.SERVER_UNREACHABLE, 1), false));
        assertEquals(1, once.get());
    }

    @Test
    void noReintentaOtrosErrores() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(UnavailableException.class,
                () -> policy.ejecutar(() -> fallar(calls, "INSUFFICIENT_NODES", 1), true));
        assertEquals(1, calls.get());
    }

    @Test
    void seRindeAlAgotarLasEsperas() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(UnavailableException.class, () -> policy.ejecutar(() -> fallar(calls, "SAFE_MODE", 99), true));
        assertEquals(3, calls.get());
    }
}
