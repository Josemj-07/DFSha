package co.edu.upb.dfsha.server.auth.domain;

public record User(
    String username, String passwordHash, String role, long quotaBytes, long usageBytes) {
  public User(String username, String passwordHash, String role, long quotaBytes) {
    this(username, passwordHash, role, quotaBytes, 0);
  }
}
