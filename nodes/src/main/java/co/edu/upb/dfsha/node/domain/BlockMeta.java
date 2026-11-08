package co.edu.upb.dfsha.node.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Lo que el nodo sabe de un bloque completo: tamaño y SHA-256 en hexadecimal. */
public record BlockMeta(BlockKey key, long length, String sha256) {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public BlockMeta {
        Objects.requireNonNull(key, "key");
        if (length < 0) {
            throw new IllegalArgumentException("length no puede ser negativo");
        }
        if (sha256 == null || !SHA256_HEX.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 debe tener 64 caracteres hexadecimales en minúscula");
        }
    }
}
