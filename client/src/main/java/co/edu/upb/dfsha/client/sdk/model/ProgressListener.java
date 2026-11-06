package co.edu.upb.dfsha.client.sdk.model;

/** Recibe el avance de una transferencia. */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NINGUNO = (done, total) -> { };

    void notificarBloque(int done, int total);
}
