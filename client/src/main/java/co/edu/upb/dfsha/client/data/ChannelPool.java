package co.edu.upb.dfsha.client.data;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Un canal gRPC por dirección, reutilizado durante toda la vida del proceso. */
public final class ChannelPool implements AutoCloseable {

    private final ChannelCredentials credentials;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public ChannelPool(ChannelCredentials credentials) {
        this.credentials = credentials;
    }

    public ManagedChannel obtener(String addr) {
        return channels.computeIfAbsent(addr, this::crear);
    }

    private ManagedChannel crear(String addr) {
        int separator = addr.lastIndexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("Dirección de nodo inválida: " + addr);
        }
        String host = addr.substring(0, separator);
        int port = Integer.parseInt(addr.substring(separator + 1));
        return Grpc.newChannelBuilderForAddress(host, port, credentials).build();
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
    }
}
