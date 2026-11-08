package co.edu.upb.dfsha.node.domain;

import java.util.regex.Pattern;

/**
 * Identifica una versión concreta de un bloque: su ID y su generación.
 * El ID llega por la red y termina formando parte de una ruta de archivo,
 * por eso se valida con una expresión estricta: así se evita el path traversal.
 */
public record BlockKey(String blockId, long gen) {

    private static final Pattern FORMATO_ID = Pattern.compile("^blk_[0-9A-Za-z-]{1,64}$");

    public BlockKey {
        if (blockId == null || !FORMATO_ID.matcher(blockId).matches()) {
            throw new IllegalArgumentException("blockId inválido");
        }
        if (gen < 1) {
            throw new IllegalArgumentException("gen debe ser mayor o igual a 1");
        }
    }

    /** Forma en que el bloque aparece en el claim "blk" del block token. */
    public String obtenerAlcance() {
        return blockId + ":" + gen;
    }
}
