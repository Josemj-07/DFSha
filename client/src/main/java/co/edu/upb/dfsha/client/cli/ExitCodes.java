package co.edu.upb.dfsha.client.cli;

import co.edu.upb.dfsha.client.sdk.error.AuthException;
import co.edu.upb.dfsha.client.sdk.error.ConflictException;
import co.edu.upb.dfsha.client.sdk.error.NotFoundException;
import co.edu.upb.dfsha.client.sdk.error.UnavailableException;

/** Códigos de salida de la CLI. */
public final class ExitCodes {

    public static final int OK = 0;
    public static final int GENERAL = 1;
    public static final int USAGE = 2;
    public static final int AUTH = 3;
    public static final int NOT_FOUND = 4;
    public static final int CONFLICT = 5;
    public static final int UNAVAILABLE = 6;

    private ExitCodes() {
    }

    public static int obtenerCodigo(Throwable error) {
        return switch (error) {
            case AuthException e -> AUTH;
            case NotFoundException e -> NOT_FOUND;
            case ConflictException e -> CONFLICT;
            case UnavailableException e -> UNAVAILABLE;
            default -> GENERAL;
        };
    }
}
