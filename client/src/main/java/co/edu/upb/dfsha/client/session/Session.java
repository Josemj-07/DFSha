package co.edu.upb.dfsha.client.session;

/** Sesión persistida. {@code accessExpiresAt} va en milisegundos desde la época. */
public record Session(String serverUrl, String username, String accessToken, String refreshToken,
                      long accessExpiresAt, String cwd) {

    public Session conTokens(String newAccess, String newRefresh, long newExpiresAt) {
        return new Session(serverUrl, username, newAccess, newRefresh, newExpiresAt, cwd);
    }

    public Session conDirectorio(String newCwd) {
        return new Session(serverUrl, username, accessToken, refreshToken, accessExpiresAt, newCwd);
    }
}
