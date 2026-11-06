package co.edu.upb.dfsha.client.sdk.model;

/** Rango inclusivo de índices de bloque. */
public record BlockRange(int first, int last) {

    public BlockRange {
        if (first < 0 || last < first) {
            throw new IllegalArgumentException("Rango de bloques inválido: " + first + "-" + last);
        }
    }

    public boolean contiene(int idx) {
        return idx >= first && idx <= last;
    }
}
