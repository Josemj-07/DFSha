package co.edu.upb.dfsha.server.cluster.infra;

import co.edu.upb.dfsha.server.config.DfsProperties;
import java.nio.file.Files;
import java.security.cert.*;
import javax.naming.ldap.LdapName;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
public class MessageAuthenticator {
  private final X509Certificate ca;

  public MessageAuthenticator(DfsProperties props) throws Exception {
    try (var stream = Files.newInputStream(props.tlsDir().resolve("ca.crt"))) {
      ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
    }
  }

  public boolean verificar(Message message, String node) {
    var p = message.getMessageProperties();
    String key = p.getReceivedRoutingKey();
    return node != null
        && node.equals(p.getReceivedUserId())
        && key != null
        && node.equals(key.substring(key.lastIndexOf('.') + 1));
  }

  public java.security.PublicKey verificarCertificado(String pem, String node) throws Exception {
    var cert =
        (X509Certificate)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(
                    new java.io.ByteArrayInputStream(
                        pem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    cert.verify(ca.getPublicKey());
    cert.checkValidity();
    boolean valid =
        new LdapName(cert.getSubjectX500Principal().getName())
            .getRdns().stream()
                .anyMatch(r -> r.getType().equalsIgnoreCase("CN") && r.getValue().equals(node));
    if (!valid) throw new CertificateException("CN no coincide con el nodo");
    return cert.getPublicKey();
  }
}
