package co.edu.upb.dfsha.client.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.upb.dfsha.client.simulator.SimCluster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    private static final String PASSWORD = "clave-secreta-123";

    @TempDir
    Path dir;

    private SimCluster cluster;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void iniciar() throws Exception {
        cluster = new SimCluster(dir.resolve("sim"), 1024 * 1024);
        cluster.obtenerServidor().crearUsuario("jose", PASSWORD);
    }

    @AfterEach
    void detener() throws Exception {
        cluster.close();
    }

    private int ejecutarConEntrada(String input, String... args) {
        out.reset();
        err.reset();
        var env = Map.of("DFS_SERVER_URL", cluster.obtenerServidor().obtenerUrl(),
                "DFS_CA_CERT", cluster.obtenerPki().obtenerCaCert().toString(),
                "DFS_HOME", dir.resolve("home").toString());
        var stdin = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        try (ClientContext context = new ClientContext(env, dir.toString(), stdin, out, err)) {
            return CommandCatalog.crearRaiz(context).execute(args);
        }
    }

    private int ejecutar(String... args) {
        return ejecutarConEntrada("", args);
    }

    private String salida() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String errores() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private void iniciarSesion() {
        assertEquals(ExitCodes.OK, ejecutarConEntrada(PASSWORD + "\n", "login", "jose"));
    }

    private Path escribirLocal(String name, String text) throws IOException {
        return Files.writeString(dir.resolve(name), text);
    }

    @Test
    void laContrasenaNuncaSeImprime() {
        iniciarSesion();
        assertFalse(salida().contains(PASSWORD));
        assertFalse(errores().contains(PASSWORD));
        assertTrue(salida().contains("Sesión iniciada como jose"));
    }

    @Test
    void noExisteOpcionParaPasarLaContrasena() {
        assertEquals(ExitCodes.USAGE, ejecutar("login", "jose", "-p", PASSWORD));
    }

    @Test
    void credencialesMalasSalenConCodigo3() {
        assertEquals(ExitCodes.AUTH, ejecutarConEntrada("mala\n", "login", "jose"));
        assertTrue(errores().contains("Usuario o contraseña incorrectos"));
    }

    @Test
    void sinSesionSaleConCodigo3() {
        assertEquals(ExitCodes.AUTH, ejecutar("ls"));
        assertTrue(errores().contains("dfs login"));
    }

    @Test
    void noEncontradoSaleConCodigo4() {
        iniciarSesion();
        assertEquals(ExitCodes.NOT_FOUND, ejecutar("stat", "nada"));
    }

    @Test
    void conflictoSaleConCodigo5() {
        iniciarSesion();
        assertEquals(ExitCodes.OK, ejecutar("mkdir", "d"));
        assertEquals(ExitCodes.CONFLICT, ejecutar("mkdir", "d"));
    }

    @Test
    void servidorCaidoSaleConCodigo6() {
        iniciarSesion();
        cluster.obtenerServidor().close();
        assertEquals(ExitCodes.UNAVAILABLE, ejecutar("mkdir", "d"));
    }

    @Test
    void usoIncorrectoSaleConCodigo2() {
        assertEquals(ExitCodes.USAGE, ejecutar("mv", "solo-uno"));
        assertEquals(ExitCodes.USAGE, ejecutar("comando-inventado"));
        assertEquals(ExitCodes.USAGE, ejecutar());
    }

    @Test
    void lasPrimitivasFueraDelShellExplicanQueHacer() {
        assertEquals(ExitCodes.USAGE, ejecutar("open", "/x", "r"));
        assertTrue(errores().contains("dfs shell"));
        assertEquals(ExitCodes.USAGE, ejecutar("lock", "/x", "--mode", "exclusive"));
    }

    @Test
    void ayudaFuncionaSinConfiguracion() {
        assertEquals(ExitCodes.OK, ejecutar("--help"));
        assertTrue(salida().contains("Uso: "));
        assertTrue(salida().contains("put"));
    }

    @Test
    void catDejaSoloLosBytesEnLaSalida() throws IOException {
        iniciarSesion();
        byte[] data = new byte[3 * 1024 * 1024 + 7];
        new java.util.Random(1).nextBytes(data);
        Path file = Files.write(dir.resolve("binario"), data);
        assertEquals(ExitCodes.OK, ejecutar("put", file.toString(), "binario"));
        assertEquals(ExitCodes.OK, ejecutar("cat", "binario"));
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    void headYTailCortanPorLineasOBytes() throws IOException {
        iniciarSesion();
        assertEquals(ExitCodes.OK, ejecutar("put", escribirLocal("t", "a\nb\nc\nd\n").toString(), "t"));
        ejecutar("head", "-n", "2", "t");
        assertEquals("a\nb\n", salida());
        ejecutar("tail", "-n", "2", "t");
        assertEquals("c\nd\n", salida());
        ejecutar("head", "-c", "3", "t");
        assertEquals("a\nb", salida());
        ejecutar("tail", "-c", "3", "t");
        assertEquals("\nd\n", salida());
    }

    @Test
    void headYTailConMenosLineasDeLasPedidas() throws IOException {
        iniciarSesion();
        ejecutar("put", escribirLocal("t", "a\nb").toString(), "t");
        ejecutar("head", "t");
        assertEquals("a\nb", salida());
        ejecutar("tail", "t");
        assertEquals("a\nb", salida());
    }

    @Test
    void tailEncuentraLineasMasAllaDeUnaLectura() throws IOException {
        iniciarSesion();
        String line = "x".repeat(70 * 1024);
        ejecutar("put", escribirLocal("t", line + "\n" + line + "\nfin\n").toString(), "t");
        ejecutar("tail", "-n", "2", "t");
        assertEquals(line + "\nfin\n", salida());
    }

    @Test
    void lsLargoMuestraLosAtributos() throws IOException {
        iniciarSesion();
        ejecutar("put", escribirLocal("t", "hola").toString(), "t");
        assertEquals(ExitCodes.OK, ejecutar("ls", "-l"));
        assertTrue(salida().contains("v1"));
        assertTrue(salida().contains(" 4 "));
        assertTrue(salida().contains("jose"));
    }

    @Test
    void cdPersisteEntreEjecuciones() {
        iniciarSesion();
        ejecutar("mkdir", "d");
        assertEquals(ExitCodes.OK, ejecutar("cd", "d"));
        ejecutar("pwd");
        assertEquals("/home/jose/d", salida().trim());
    }

    @Test
    void logoutCierraLaSesion() {
        iniciarSesion();
        assertEquals(ExitCodes.OK, ejecutar("logout"));
        assertEquals(ExitCodes.AUTH, ejecutar("ls"));
    }

    @Test
    void administracionListaNodosYCreaUsuarios() {
        iniciarSesion();
        assertEquals(ExitCodes.OK, ejecutar("admin", "nodos"));
        assertTrue(salida().contains("dn-3"));
        assertEquals(ExitCodes.OK, ejecutarConEntrada("otra-clave-123\n", "admin", "crear-usuario", "ana"));
        assertFalse(salida().contains("otra-clave-123"));
        assertEquals(ExitCodes.OK, ejecutarConEntrada("otra-clave-123\n", "login", "ana"));
    }

    @Test
    void verboseMuestraElDetalleDelError() {
        ejecutar("--verbose", "ls");
        assertTrue(errores().contains("NotAuthenticatedException"));
        ejecutar("ls");
        assertFalse(errores().contains("NotAuthenticatedException"));
    }
}
