package co.edu.upb.dfsha.server.files.application;

public record OpenRequest(String path, String mode, Boolean create, Boolean exclusive) {
  public OpenRequest {
    create = Boolean.TRUE.equals(create);
    exclusive = Boolean.TRUE.equals(exclusive);
  }
}
