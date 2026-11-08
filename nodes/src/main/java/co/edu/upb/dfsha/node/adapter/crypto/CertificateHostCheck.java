package co.edu.upb.dfsha.node.adapter.crypto;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

/**
 * Comprueba al arrancar que la dirección que el nodo anuncia esté en el SAN de su certificado.
 * Si no está, los clientes rechazarían cada conexión TLS con un error difícil de entender; es
 * mejor detener el nodo con un mensaje claro.
 */
public final class CertificateHostCheck {

    private static final int SAN_DNS = 2;
    private static final int SAN_IP = 7;

    private CertificateHostCheck() {
    }

    public static void verificar(X509Certificate certificado, String host) {
        if (!contiene(certificado, host)) {
            throw new IllegalStateException("DFS_ADVERTISED_HOST=" + host + " no está en el SAN del certificado "
                    + "del nodo: los clientes fallarían el handshake TLS. Reemite el certificado con ese nombre o IP.");
        }
    }

    private static boolean contiene(X509Certificate certificado, String host) {
        Collection<List<?>> nombres;
        try {
            nombres = certificado.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            throw new IllegalStateException("el certificado del nodo tiene un SAN inválido", e);
        }
        if (nombres == null) {
            return false;
        }
        for (List<?> nombre : nombres) {
            Object tipo = nombre.get(0);
            boolean dnsOIp = tipo.equals(SAN_DNS) || tipo.equals(SAN_IP);
            if (dnsOIp && host.equalsIgnoreCase(String.valueOf(nombre.get(1)))) {
                return true;
            }
        }
        return false;
    }
}
