package co.edu.upb.dfsha.node.config;

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

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/** CA de prueba con el mismo perfil que server/pki (EC P-256), igual que SimPki del cliente. */
final class TestPki {

    record Identity(Path certFile, Path keyFile) {
    }

    private static final AtomicLong SERIAL = new AtomicLong(System.currentTimeMillis());

    private final Path dir;
    private final KeyPair caKeys;
    private final X509Certificate caCert;

    TestPki(Path dir, String nombreCa) {
        try {
            this.dir = dir;
            Files.createDirectories(dir);
            this.caKeys = crearClaves();
            this.caCert = firmar(new X500Name("O=DFSha,CN=" + nombreCa), caKeys, null, true);
            escribirPem(obtenerCaCert(), caCert);
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo crear la CA de prueba", e);
        }
    }

    Path obtenerCaCert() {
        return dir.resolve("ca.crt");
    }

    Identity emitir(String nombre, String... dnsNames) {
        try {
            KeyPair claves = crearClaves();
            X509Certificate certificado = firmar(new X500Name("O=DFSha,CN=" + nombre), claves, dnsNames, false);
            Path cert = dir.resolve(nombre + ".crt");
            Path key = dir.resolve(nombre + ".key");
            escribirPem(cert, certificado);
            escribirPem(key, new JcaPKCS8Generator(claves.getPrivate(), null).generate());
            return new Identity(cert, key);
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo emitir el certificado de " + nombre, e);
        }
    }

    private static KeyPair crearClaves() throws GeneralSecurityException {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("EC");
        generador.initialize(new ECGenParameterSpec("secp256r1"));
        return generador.generateKeyPair();
    }

    private X509Certificate firmar(X500Name sujeto, KeyPair claves, String[] dnsNames, boolean ca) throws Exception {
        X500Name emisor = ca ? sujeto : X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
        Instant ahora = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(emisor, BigInteger.valueOf(SERIAL.incrementAndGet()),
                Date.from(ahora.minus(Duration.ofHours(1))), Date.from(ahora.plus(Duration.ofDays(1))), sujeto,
                claves.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
        if (dnsNames != null) {
            GeneralName[] nombres = new GeneralName[dnsNames.length];
            for (int i = 0; i < dnsNames.length; i++) {
                nombres[i] = new GeneralName(GeneralName.dNSName, dnsNames[i]);
            }
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(nombres));
        }
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(caKeys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static void escribirPem(Path archivo, Object valor) throws IOException {
        StringWriter texto = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(texto)) {
            writer.writeObject(valor);
        }
        Files.writeString(archivo, texto.toString());
    }
}
