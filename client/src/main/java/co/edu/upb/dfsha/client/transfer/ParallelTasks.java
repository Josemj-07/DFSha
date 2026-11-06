package co.edu.upb.dfsha.client.transfer;

import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/** Ejecuta tareas con paralelismo acotado. El primer fallo cancela las demás. */
final class ParallelTasks implements AutoCloseable {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final List<Future<?>> tasks = new ArrayList<>();

    ParallelTasks(int parallelism) {
        this.permits = new Semaphore(parallelism);
    }

    /** Espera un cupo libre antes de lanzar la tarea, para no adelantarse a los bloques en curso. */
    void enviar(Runnable task) {
        lanzarSiFallo();
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abortar(new UnavailableException("INTERRUPTED", "Operación interrumpida", e));
        }
        lanzarSiFallo();
        synchronized (tasks) {
            tasks.add(executor.submit(() -> ejecutar(task)));
        }
    }

    private void ejecutar(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            abortar(e);
        } finally {
            permits.release();
        }
    }

    /** Registra el primer fallo y cancela las tareas en curso. Se puede llamar desde otro hilo. */
    void abortar(RuntimeException error) {
        if (failure.compareAndSet(null, error)) {
            synchronized (tasks) {
                tasks.forEach(task -> task.cancel(true));
            }
        }
    }

    void esperar() {
        List<Future<?>> snapshot;
        synchronized (tasks) {
            snapshot = List.copyOf(tasks);
        }
        snapshot.forEach(this::esperarTarea);
        lanzarSiFallo();
    }

    private void esperarTarea(Future<?> task) {
        try {
            task.get();
        } catch (CancellationException | ExecutionException e) {
            lanzarSiFallo();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abortar(new UnavailableException("INTERRUPTED", "Operación interrumpida", e));
        }
    }

    private void lanzarSiFallo() {
        RuntimeException error = failure.get();
        if (error != null) {
            throw error;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
