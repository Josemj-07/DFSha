package co.edu.upb.dfsha.client.sdk.model;

import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;

/** Modos de apertura de un archivo. */
public enum OpenMode {
    READ("r"), WRITE("w"), APPEND("a"), READ_WRITE("rw");

    private final String wire;

    OpenMode(String wire) {
        this.wire = wire;
    }

    public String obtenerValor() {
        return wire;
    }

    public boolean permiteEscritura() {
        return this != READ;
    }

    public static OpenMode desdeValor(String value) {
        for (OpenMode mode : values()) {
            if (mode.wire.equals(value)) {
                return mode;
            }
        }
        throw new InvalidOperationException("INVALID_MODE", "Modo inválido: " + value + ". Usa r, w, a o rw");
    }
}
