package co.edu.upb.dfsha.node.adapter.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;

/** Datos de prueba compartidos por las pruebas del almacén. */
final class StoreFixtures {

    private StoreFixtures() {
    }

    static byte[] crearDatos(int tamanio) {
        byte[] datos = new byte[tamanio];
        new Random(42).nextBytes(datos);
        return datos;
    }

    static String calcularSha256(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
