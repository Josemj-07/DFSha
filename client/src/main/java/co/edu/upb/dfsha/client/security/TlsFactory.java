package co.edu.upb.dfsha.client.security;

import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import io.grpc.ChannelCredentials;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettySslContextChannelCredentials;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/** Crea los contextos TLS. Confía solo en la CA del proyecto y solo acepta TLS 1.3. */
public final class TlsFactory {

    private static final String TLS_VERSION = "TLSv1.3";

    private final Path caCert;

    public TlsFactory(Path caCert) {
        if (!Files.isRegularFile(caCert) || !Files.isReadable(caCert)) {
            throw new InvalidOperationException("MISSING_CA", "No se puede leer el certificado de la CA: " + caCert);
        }
        this.caCert = caCert;
    }

    public SSLContext crearContextoHttp() {
        try (InputStream in = Files.newInputStream(caCert)) {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("dfsha-ca",
                    CertificateFactory.getInstance("X.509").generateCertificate(in));
            var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext context = SSLContext.getInstance(TLS_VERSION);
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new InvalidOperationException("INVALID_CA", "Certificado de CA inválido: " + caCert, e);
        }
    }

    public SSLParameters crearParametrosHttp() {
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(new String[] {TLS_VERSION});
        return parameters;
    }

    public ChannelCredentials crearCredencialesGrpc() {
        try {
            SslContext context = GrpcSslContexts.forClient()
                    .trustManager(caCert.toFile())
                    .protocols(TLS_VERSION)
                    .build();
            return NettySslContextChannelCredentials.create(context);
        } catch (IOException e) {
            throw new InvalidOperationException("INVALID_CA", "Certificado de CA inválido: " + caCert, e);
        }
    }
}
