package co.edu.upb.dfsha.client.cli.admin;

import co.edu.upb.dfsha.client.cli.ClientCommand;
import co.edu.upb.dfsha.client.cli.ClientContext;
import co.edu.upb.dfsha.client.cli.PasswordReader;
import java.util.Arrays;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Crea un usuario. */
@Command(name = "crear-usuario", mixinStandardHelpOptions = true, description = "Crea un usuario.")
public final class CreateUserCommand extends ClientCommand {

    @Option(names = "--admin", description = "Le da el rol ADMIN")
    private boolean admin;

    @Parameters(index = "0", paramLabel = "USUARIO", description = "Usuario")
    private String username;

    public CreateUserCommand(ClientContext context) {
        super(context);
    }

    @Override
    protected void ejecutar() {
        char[] password = new PasswordReader(context).leer("Contraseña del usuario nuevo: ");
        try {
            cliente().crearUsuario(username, password, admin ? "ADMIN" : "USER");
            salida().println("Usuario creado: " + username);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
