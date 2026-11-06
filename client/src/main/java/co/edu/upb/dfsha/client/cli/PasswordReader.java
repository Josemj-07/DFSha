package co.edu.upb.dfsha.client.cli;

import co.edu.upb.dfsha.client.sdk.error.InvalidOperationException;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Lee contraseñas sin eco. Nunca se aceptan como argumento: quedarían en el historial y en {@code ps}. */
public final class PasswordReader {

    private final ClientContext context;

    public PasswordReader(ClientContext context) {
        this.context = context;
    }

    public char[] leer(String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readPassword("%s", prompt);
        }
        return leerDeEntrada();
    }

    private char[] leerDeEntrada() {
        try {
            var reader = new BufferedReader(new InputStreamReader(context.obtenerEntrada(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                throw new InvalidOperationException("MISSING_PASSWORD", "No se recibió la contraseña");
            }
            return line.toCharArray();
        } catch (IOException e) {
            throw new InvalidOperationException("MISSING_PASSWORD", "No se pudo leer la contraseña", e);
        }
    }
}
