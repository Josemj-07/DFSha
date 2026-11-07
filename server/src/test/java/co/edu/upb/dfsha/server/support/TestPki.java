package co.edu.upb.dfsha.server.support;

import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.openssl.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public class TestPki {
  private final Path dir;
  private final KeyPair caKeys;
  private final X509Certificate ca;

  public TestPki(Path dir) throws Exception {
    this.dir = dir;
    Files.createDirectories(dir);
    caKeys = generar();
    ca = firmar("CA", caKeys, true);
    escribir(dir.resolve("ca.crt"), ca);
    var server = generar();
    escribir(dir.resolve("tls.crt"), firmar("localhost", server, false));
    escribir(dir.resolve("tls.key"), new JcaPKCS8Generator(server.getPrivate(), null).generate());
    Files.createDirectories(dir.resolve("keys"));
    for (String name : java.util.List.of("jwt", "blocktoken")) {
      var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      escribir(
          dir.resolve("keys/" + name + ".key"),
          new JcaPKCS8Generator(keys.getPrivate(), null).generate());
      escribir(dir.resolve("keys/" + name + ".pub"), keys.getPublic());
    }
  }

  public KeyPair generar() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    return generator.generateKeyPair();
  }

  public X509Certificate firmar(String name, KeyPair pair, boolean root) throws Exception {
    var subject = new X500Name("O=DFSha,CN=" + name);
    var issuer = root ? subject : X500Name.getInstance(ca.getSubjectX500Principal().getEncoded());
    var builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            new BigInteger(120, new SecureRandom()),
            Date.from(Instant.now().minusSeconds(3600)),
            Date.from(Instant.now().plusSeconds(86400)),
            subject,
            pair.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(root));
    if (!root)
      builder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(
              new GeneralName[] {
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")
              }));
    return new JcaX509CertificateConverter()
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withECDSA").build(caKeys.getPrivate())));
  }

  public String obtenerPem(X509Certificate cert) throws Exception {
    var text = new java.io.StringWriter();
    try (var writer = new JcaPEMWriter(text)) {
      writer.writeObject(cert);
    }
    return text.toString();
  }

  private void escribir(Path path, Object value) throws Exception {
    try (var writer = new JcaPEMWriter(Files.newBufferedWriter(path))) {
      writer.writeObject(value);
    }
  }
}
