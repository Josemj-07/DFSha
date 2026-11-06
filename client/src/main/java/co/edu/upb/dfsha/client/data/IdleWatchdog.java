package co.edu.upb.dfsha.client.data;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cancela una transferencia que deja de avanzar. No es un plazo total: una transferencia lenta
 * pero sana nunca se corta, porque cada avance reinicia la cuenta.
 */
final class IdleWatchdog {

    private static final long CHECK_DIVISOR = 4;

    private final Runnable onExpired;
    private volatile long limitNanos;
    private volatile long lastProgress = System.nanoTime();
    private ScheduledFuture<?> task;

    IdleWatchdog(Duration limit, Runnable onExpired) {
        this.limitNanos = limit.toNanos();
        this.onExpired = onExpired;
    }

    void iniciar(ScheduledExecutorService scheduler) {
        long period = Math.max(1, limitNanos / CHECK_DIVISOR);
        task = scheduler.scheduleWithFixedDelay(this::revisar, period, period, TimeUnit.NANOSECONDS);
    }

    void registrarAvance() {
        lastProgress = System.nanoTime();
    }

    void cambiarLimite(Duration limit) {
        limitNanos = limit.toNanos();
        registrarAvance();
    }

    void detener() {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void revisar() {
        if (System.nanoTime() - lastProgress > limitNanos) {
            detener();
            onExpired.run();
        }
    }
}
