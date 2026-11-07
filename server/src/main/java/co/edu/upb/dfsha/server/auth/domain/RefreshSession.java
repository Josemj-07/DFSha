package co.edu.upb.dfsha.server.auth.domain;

import java.time.Instant;

public record RefreshSession(String hash, String username, Instant expiresAt) {}
