package co.edu.upb.dfsha.node.domain;

import java.util.Objects;
import java.util.Set;

/** Permiso que concede un block token ya verificado: quién, qué operaciones y sobre qué bloques. */
public record BlockGrant(String subject, Set<Op> ops, Set<String> blocks) {

    public BlockGrant {
        Objects.requireNonNull(subject, "subject");
        ops = Set.copyOf(ops);
        blocks = Set.copyOf(blocks);
    }

    public boolean permite(Op op, BlockKey key) {
        return ops.contains(op) && blocks.contains(key.obtenerAlcance());
    }
}
