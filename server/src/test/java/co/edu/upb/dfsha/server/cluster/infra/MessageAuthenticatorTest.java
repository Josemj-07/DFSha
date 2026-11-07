package co.edu.upb.dfsha.server.cluster.infra;

import static org.junit.jupiter.api.Assertions.*;

import co.edu.upb.dfsha.server.config.DfsProperties;
import co.edu.upb.dfsha.server.support.TestPki;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.*;

class MessageAuthenticatorTest {
  @TempDir Path dir;

  @Test
  void validarIdentidadDeMensaje() throws Exception {
    new TestPki(dir);
    var auth = new MessageAuthenticator(new DfsProperties(dir, dir, 128, "password"));
    var props = new MessageProperties();
    props.setReceivedUserId("dn-1");
    props.setReceivedRoutingKey("node.heartbeat.dn-1");
    var message = new Message(new byte[0], props);
    assertTrue(auth.verificar(message, "dn-1"));
    assertFalse(auth.verificar(message, "dn-2"));
    props.setReceivedRoutingKey("node.report.dn-2");
    assertFalse(auth.verificar(message, "dn-1"));
  }

  @Test
  void rechazarOtraCaYNombreDistinto() throws Exception {
    var pki = new TestPki(dir);
    var auth = new MessageAuthenticator(new DfsProperties(dir, dir, 128, "password"));
    var keys = pki.generar();
    var cert = pki.firmar("dn-1", keys, false);
    assertEquals(keys.getPublic(), auth.verificarCertificado(pki.obtenerPem(cert), "dn-1"));
    assertThrows(Exception.class, () -> auth.verificarCertificado(pki.obtenerPem(cert), "dn-2"));
    var other = new TestPki(dir.resolve("other"));
    var foreign = other.firmar("dn-1", other.generar(), false);
    assertThrows(
        Exception.class, () -> auth.verificarCertificado(other.obtenerPem(foreign), "dn-1"));
  }
}
