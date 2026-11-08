package co.edu.upb.dfsha.node.domain;

/** Operaciones que puede autorizar un block token (claim "ops": "r" o "w"). */
public enum Op {
    READ("r"),
    WRITE("w");

    private final String codigo;

    Op(String codigo) {
        this.codigo = codigo;
    }

    public static Op interpretar(String codigo) {
        for (Op op : values()) {
            if (op.codigo.equals(codigo)) {
                return op;
            }
        }
        throw new IllegalArgumentException("operación desconocida: " + codigo);
    }
}
