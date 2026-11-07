package co.edu.upb.dfsha.server.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dfs")
public record DfsProperties(Path dataDir, Path tlsDir, long blockSizeBytes, String adminPassword) {}
