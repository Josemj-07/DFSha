package co.edu.upb.dfsha.node.port;

/** Limita cuántos bytes por segundo puede leer o enviar una tarea de fondo. */
@FunctionalInterface
public interface Throttle {

    /** Espera lo necesario antes de procesar esa cantidad de bytes. */
    void adquirir(long bytes) throws InterruptedException;
}
