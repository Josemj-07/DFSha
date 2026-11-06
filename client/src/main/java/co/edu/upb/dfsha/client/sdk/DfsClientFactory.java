package co.edu.upb.dfsha.client.sdk;

import co.edu.upb.dfsha.client.config.ClientConfig;
import co.edu.upb.dfsha.client.data.ChannelPool;
import co.edu.upb.dfsha.client.data.GrpcBlockGateway;
import co.edu.upb.dfsha.client.data.GrpcTimeouts;
import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.LockGateway;
import co.edu.upb.dfsha.client.metadata.RestAdminGateway;
import co.edu.upb.dfsha.client.metadata.RestAuthGateway;
import co.edu.upb.dfsha.client.metadata.RestFileSessionGateway;
import co.edu.upb.dfsha.client.metadata.RestInvoker;
import co.edu.upb.dfsha.client.metadata.RestLockGateway;
import co.edu.upb.dfsha.client.metadata.RestNamespaceGateway;
import co.edu.upb.dfsha.client.metadata.RetryPolicy;
import co.edu.upb.dfsha.client.metadata.TokenSource;
import co.edu.upb.dfsha.client.security.TlsFactory;
import co.edu.upb.dfsha.client.session.LeaseKeeper;
import co.edu.upb.dfsha.client.session.LocationCache;
import co.edu.upb.dfsha.client.session.SessionStore;
import co.edu.upb.dfsha.client.session.TokenManager;
import co.edu.upb.dfsha.client.transfer.BlockFetcher;
import co.edu.upb.dfsha.client.transfer.BlockPublisher;
import co.edu.upb.dfsha.client.transfer.DirtyBlockUploader;
import co.edu.upb.dfsha.client.transfer.Downloader;
import co.edu.upb.dfsha.client.transfer.TransferOptions;
import co.edu.upb.dfsha.client.transfer.Uploader;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** Raíz de composición: conecta las capas del cliente. */
public final class DfsClientFactory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LEASE_INTERVAL = Duration.ofSeconds(10);

    private DfsClientFactory() {
    }

    public static DfsClient crear(ClientConfig config, Consumer<String> warnings) {
        TlsFactory tls = new TlsFactory(config.caCert());
        HttpClient http = HttpClient.newBuilder()
                .sslContext(tls.crearContextoHttp())
                .sslParameters(tls.crearParametrosHttp())
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        Clock clock = Clock.systemUTC();
        RetryPolicy retries = new RetryPolicy();
        SessionStore sessions = new SessionStore(config.homeDir(), warnings);
        var auth = new RestAuthGateway(new RestInvoker(http, config.serverUrl(), sinToken(), retries));
        var tokens = new TokenManager(auth, sessions, config.serverUrl(), clock);
        RestInvoker rest = new RestInvoker(http, config.serverUrl(), tokens, retries);

        FileSessionGateway files = new RestFileSessionGateway(rest);
        LockGateway locks = new RestLockGateway(rest);
        ChannelPool channels = new ChannelPool(tls.crearCredencialesGrpc());
        var blocks = new GrpcBlockGateway(channels::obtener, GrpcTimeouts.porDefecto());
        var keeper = new LeaseKeeper(files, locks, LEASE_INTERVAL, warnings);
        TransferOptions options = TransferOptions.porDefecto();
        var publisher = new BlockPublisher(blocks, files, options);
        var fetcher = new BlockFetcher(blocks, files);
        var services = new RemoteFile.Services(files, locks, new DirtyBlockUploader(files, publisher, options),
                fetcher, keeper, new LocationCache(clock));
        return new DfsClient(new DfsClient.Parts(tokens, sessions, new RestNamespaceGateway(rest),
                new RestAdminGateway(rest), new Uploader(files, publisher, keeper, options),
                new Downloader(files, fetcher, keeper, options), services, config.homeDir().resolve("tmp"),
                List.of(keeper, blocks, channels)));
    }

    private static TokenSource sinToken() {
        return new TokenSource() {
            @Override
            public String obtenerToken() {
                throw new IllegalStateException("Las rutas de sesión no llevan token");
            }

            @Override
            public String renovarTrasRechazo(String rejectedToken) {
                throw new IllegalStateException("Las rutas de sesión no llevan token");
            }
        };
    }
}
