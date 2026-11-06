package co.edu.upb.dfsha.client.metadata;

import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/** Reintentos con espera creciente, hasta unos 30 segundos en total. */
public final class RetryPolicy {

    public static final String SERVER_UNREACHABLE = "SERVER_UNREACHABLE";
    private static final String SAFE_MODE = "SAFE_MODE";
    private static final List<Duration> DEFAULT_WAITS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(8), Duration.ofSeconds(8));

    /** Espera entre intentos; se inyecta para que las pruebas no duerman. */
    @FunctionalInterface
    public interface Sleeper {
        void dormir(Duration duration) throws InterruptedException;
    }

    private final List<Duration> waits;
    private final Sleeper sleeper;

    public RetryPolicy() {
        this(DEFAULT_WAITS, duration -> Thread.sleep(duration));
    }

    public RetryPolicy(List<Duration> waits, Sleeper sleeper) {
        this.waits = List.copyOf(waits);
        this.sleeper = sleeper;
    }

    public <T> T ejecutar(Supplier<T> operation, boolean idempotent) {
        for (int attempt = 0; ; attempt++) {
            try {
                return operation.get();
            } catch (UnavailableException e) {
                if (attempt >= waits.size() || !esReintentable(e, idempotent)) {
                    throw e;
                }
                esperar(waits.get(attempt), e);
            }
        }
    }

    /** {@code SAFE_MODE} es un rechazo seguro; un fallo de conexión deja la duda de si se aplicó. */
    private boolean esReintentable(UnavailableException error, boolean idempotent) {
        String code = error.obtenerCodigo();
        return SAFE_MODE.equals(code) || (idempotent && SERVER_UNREACHABLE.equals(code));
    }

    private void esperar(Duration wait, UnavailableException cause) {
        try {
            sleeper.dormir(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }
}
