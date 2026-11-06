package co.edu.upb.dfsha.client.session;

import co.edu.upb.dfsha.client.metadata.BlockInfo;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Ubicaciones de bloques por archivo y versión, válidas durante 60 segundos. */
public final class LocationCache {

    private static final Duration TTL = Duration.ofSeconds(60);

    private record Key(String path, long version) {
    }

    private record Entry(List<BlockInfo> blocks, long expiresAt) {
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public LocationCache(Clock clock) {
        this.clock = clock;
    }

    public void guardar(String path, long version, List<BlockInfo> blocks) {
        entries.put(new Key(path, version), new Entry(List.copyOf(blocks), clock.millis() + TTL.toMillis()));
    }

    public Optional<List<BlockInfo>> obtener(String path, long version) {
        Key key = new Key(path, version);
        Entry entry = entries.get(key);
        if (entry == null || entry.expiresAt() <= clock.millis()) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.blocks());
    }

    public void invalidar(String path, long version) {
        entries.remove(new Key(path, version));
    }
}
