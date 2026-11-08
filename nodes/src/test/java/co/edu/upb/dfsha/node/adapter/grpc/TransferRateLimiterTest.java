package co.edu.upb.dfsha.node.adapter.grpc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferRateLimiterTest {

    @Test
    void noDejaPasarMasDeLaVelocidadConfigurada() throws InterruptedException {
        var limitador = new TransferRateLimiter(10_000_000);
        long inicio = System.nanoTime();

        for (int i = 0; i < 3; i++) {
            limitador.adquirir(1_000_000);
        }

        // 3 MB a 10 MB/s: el primero pasa de inmediato y los otros esperan unos 200 ms en total.
        long milis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
        assertTrue(milis >= 150, "tardó solo " + milis + " ms");
    }

    @Test
    void rechazaUnaVelocidadInvalida() {
        assertThrows(IllegalArgumentException.class, () -> new TransferRateLimiter(0));
    }
}
