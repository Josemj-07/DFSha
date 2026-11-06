package co.edu.upb.dfsha.client.cli.commands;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.cli.PasswordReader;
import java.util.Arrays;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Inicia sesión. La contraseña se pide por consola; no existe opción para pasarla como argumento. */
@Command(name = "login", mixinStandardHelpOptions = true, description = "Inicia sesión.")
public final class LoginCommand extends ClientCommand {

    @Parameters(index = "0", arity = "0..1", paramLabel = "USUARIO", description = "Usuario")
    private String username;

    public LoginCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        String user = username != null ? username : System.getProperty("user.name");
        char[] password = new PasswordReader(context).leer("Contraseña: ");
        try {
            cliente().iniciarSesion(user, password);
            salida().println("Sesión iniciada como " + user);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
