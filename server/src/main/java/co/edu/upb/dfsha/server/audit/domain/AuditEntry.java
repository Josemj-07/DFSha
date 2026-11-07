package co.edu.upb.dfsha.server.audit.domain;

import java.time.Instant;

public record AuditEntry(
    Instant time, String user, String action, String ip, String result, String traceId) {}
