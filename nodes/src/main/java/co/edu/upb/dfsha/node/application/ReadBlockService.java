package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.domain.Op;
import co.edu.upb.dfsha.node.domain.PermissionDeniedException;
import co.edu.upb.dfsha.node.port.BlockStore;

/**
 * Caso de uso de lectura: decide si se puede leer y qué rango exacto. No sabe nada de gRPC;
 * el adaptador solo se encarga de enviar los bytes.
 */
public class ReadBlockService {

    private final BlockStore store;

    public ReadBlockService(BlockStore store) {
        this.store = store;
    }

    /**
     * @param length 0 significa "hasta el final del bloque", como en ReadRequest del contrato
     */
    public BlockRead abrir(BlockGrant grant, BlockKey key, long offset, long length) {
        if (!grant.permite(Op.READ, key)) {
            throw new PermissionDeniedException("el token no permite leer " + key.obtenerAlcance());
        }
        // Un bloque en cuarentena o de otra generación no aparece aquí: responde NOT_FOUND.
        BlockMeta meta = store.buscar(key)
                .orElseThrow(() -> new BlockNotFoundException("el bloque " + key.obtenerAlcance() + " no existe"));
        long aLeer = calcularLongitud(meta, offset, length);
        return new BlockRead(meta, offset, aLeer, store.abrir(key, offset));
    }

    private static long calcularLongitud(BlockMeta meta, long offset, long length) {
        if (offset < 0 || offset > meta.length()) {
            throw new IllegalArgumentException("offset " + offset + " fuera del bloque de " + meta.length() + " bytes");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length no puede ser negativo");
        }
        long disponible = meta.length() - offset;
        if (length > disponible) {
            throw new IllegalArgumentException("el rango pedido excede el bloque en " + (length - disponible) + " bytes");
        }
        return length == 0 ? disponible : length;
    }
}
