package co.edu.upb.dfsha.client.integrity;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32C;

/** Funciones de suma de verificación usadas en el protocolo. */
public final class Checksums {

    private Checksums() {
    }

    /** Devuelve el CRC32C como {@code int}, porque en el proto es {@code fixed32}. */
    public static int calcularCrc32c(ByteBuffer data) {
        CRC32C crc = new CRC32C();
        crc.update(data.duplicate());
        return (int) crc.getValue();
    }

    public static MessageDigest crearSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no incluye SHA-256", e);
        }
    }

    public static String convertirAHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    public static boolean sonIguales(String hexA, String hexB) {
        return hexA != null && hexB != null && MessageDigest.isEqual(
                hexA.toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                hexB.toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
