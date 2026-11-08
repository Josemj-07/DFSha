package co.edu.upb.dfsha.node.port;

import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.Receipt;

/** Firma recibos con la clave privada TLS del nodo. */
public interface ReceiptSigner {

    Receipt firmar(BlockMeta meta);
}
