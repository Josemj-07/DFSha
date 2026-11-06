package co.edu.upb.dfsha.client.simulator;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Autoridad certificadora de prueba, con el mismo perfil que {@code server/pki}: EC P-256. */
public final class SimPki {

    /** Identidad emitida por la CA. */
    public record Identity(KeyPair keys, X509Certificate certificate, Path certFile, Path keyFile) {
    }

    private static final AtomicLong SERIAL = new AtomicLong(System.currentTimeMillis());

    private final Path dir;
    private final KeyPair caKeys;
    private final X509Certificate caCert;

    public SimPki(Path dir, String caName) {
        try {
            this.dir = dir;
            Files.createDirectories(dir);
            this.caKeys = crearClaves();
            this.caCert = firmar(new X500Name("O=DFSha,CN=" + caName), caKeys, null, true);
            escribirPem(obtenerCaCert(), caCert);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear la CA de prueba", e);
        }
    }

    public Path obtenerCaCert() {
        return dir.resolve("ca.crt");
    }

    /** @param sanNames nombres DNS del certificado; las IP se escriben como tales */
    public Identity emitir(String name, String... sanNames) {
        try {
            KeyPair keys = crearClaves();
            X509Certificate certificate = firmar(new X500Name("O=DFSha,CN=" + name), keys, sanNames, false);
            Path cert = dir.resolve(name + ".crt");
            Path key = dir.resolve(name + ".key");
            escribirPem(cert, certificate);
            escribirPem(key, new JcaPKCS8Generator(keys.getPrivate(), null).generate());
            return new Identity(keys, certificate, cert, key);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo emitir el certificado de " + name, e);
        }
    }

    public SSLContext crearContextoServidor(Identity identity) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("tls", identity.keys().getPrivate(), new char[0],
                new Certificate[] {identity.certificate(), caCert});
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    private KeyPair crearClaves() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private X509Certificate firmar(X500Name subject, KeyPair subjectKeys, String[] sans, boolean ca) throws Exception {
        X500Name issuer = ca ? subject : X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(SERIAL.incrementAndGet()),
                Date.from(now.minus(Duration.ofHours(1))), Date.from(now.plus(Duration.ofDays(1))), subject,
                subjectKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(
                ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
        if (sans != null) {
            GeneralName[] names = new GeneralName[sans.length];
            for (int i = 0; i < sans.length; i++) {
                int type = sans[i].matches("[0-9.]+") ? GeneralName.iPAddress : GeneralName.dNSName;
                names[i] = new GeneralName(type, sans[i]);
            }
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
        }
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(caKeys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private void escribirPem(Path file, Object value) throws IOException {
        StringWriter text = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(text)) {
            writer.writeObject(value);
        }
        Files.writeString(file, text.toString());
    }
}
