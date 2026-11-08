package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.port.Throttle;

/**
 * Limita la velocidad de las re-replicaciones (50 MB/s por defecto) para que una reparación
 * masiva no deje sin red ni disco a los clientes. También lo usa el scrubber para leer el disco
 * sin competir con las lecturas de los clientes.
 */
public class TransferRateLimiter implements Throttle {

    private final double nanosPorByte;
    private long siguienteLibre = System.nanoTime();

    public TransferRateLimiter(long bytesPorSegundo) {
        if (bytesPorSegundo <= 0) {
            throw new IllegalArgumentException("la velocidad debe ser positiva");
        }
        this.nanosPorByte = 1_000_000_000.0 / bytesPorSegundo;
    }

    /** Bloquea el hilo lo necesario para que el total enviado no supere la velocidad. */
    @Override
    public void adquirir(long bytes) throws InterruptedException {
        long espera;
        synchronized (this) {
            long ahora = System.nanoTime();
            long inicio = Math.max(ahora, siguienteLibre);
            siguienteLibre = inicio + (long) (bytes * nanosPorByte);
            espera = inicio - ahora;
        }
        if (espera > 0) {
            Thread.sleep(espera / 1_000_000, (int) (espera % 1_000_000));
        }
    }
}
