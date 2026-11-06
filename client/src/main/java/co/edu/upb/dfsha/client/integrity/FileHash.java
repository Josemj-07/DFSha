package co.edu.upb.dfsha.client.integrity;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hash de un archivo: SHA-256 de los 32 bytes crudos del hash de cada bloque, en orden de índice.
 * Admite agregar bloques en cualquier orden, porque se transfieren en paralelo.
 */
public final class FileHash {

    private final Map<Integer, String> blocks = new TreeMap<>();

    public synchronized void agregarBloque(int idx, String sha256Hex) {
        blocks.put(idx, sha256Hex);
    }

    public synchronized String calcular() {
        MessageDigest digest = Checksums.crearSha256();
        for (String sha256Hex : blocks.values()) {
            digest.update(HexFormat.of().parseHex(sha256Hex));
        }
        return Checksums.convertirAHex(digest.digest());
    }
}
