package co.edu.upb.dfsha.client.sdk.model;

/** Entrada del registro de auditoría. */
public record AuditEntry(String time, String user, String action, String ip, String result, String traceId) {
}
