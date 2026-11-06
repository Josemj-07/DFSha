package co.edu.upb.dfsha.client.data;

import java.time.Duration;

/**
 * Tiempos límite de una transferencia.
 *
 * @param inactivity tiempo máximo sin avance mientras fluyen datos
 * @param confirmation espera tras enviar el último fragmento: el nodo debe hacer fsync y esperar la cadena
 */
public record GrpcTimeouts(Duration inactivity, Duration confirmation) {

    public static GrpcTimeouts porDefecto() {
        return new GrpcTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(30));
    }
}
