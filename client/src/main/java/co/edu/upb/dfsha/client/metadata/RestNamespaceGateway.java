package co.edu.upb.dfsha.client.metadata;

import static co.edu.upb.dfsha.client.metadata.QueryString.codificar;

import co.edu.upb.dfsha.client.sdk.model.FileInfo;
import java.util.Map;

/** Espacio de nombres por REST. */
public final class RestNamespaceGateway implements NamespaceGateway {

    private final RestInvoker rest;

    public RestNamespaceGateway(RestInvoker rest) {
        this.rest = rest;
    }

    @Override
    public DirectoryPage listar(String path, String cursor) {
        String query = "/fs/ls?path=" + codificar(path) + (cursor == null ? "" : "&cursor=" + codificar(cursor));
        return rest.ejecutar(RestRequest.consultar(query), DirectoryPage.class);
    }

    @Override
    public FileInfo obtenerAtributos(String path) {
        return rest.ejecutar(RestRequest.consultar("/fs/stat?path=" + codificar(path)), FileInfo.class);
    }

    @Override
    public void crearDirectorio(String path, boolean parents) {
        rest.ejecutarSinRespuesta(RestRequest.enviar("/fs/mkdir", Map.of("path", path, "parents", parents)));
    }

    @Override
    public void borrarDirectorio(String path, boolean recursive) {
        rest.ejecutarSinRespuesta(RestRequest.borrar(
                "/fs/rmdir?path=" + codificar(path) + "&recursive=" + recursive));
    }

    @Override
    public void borrar(String path) {
        rest.ejecutarSinRespuesta(RestRequest.borrar("/fs/files?path=" + codificar(path)));
    }

    @Override
    public void mover(String from, String to) {
        rest.ejecutarSinRespuesta(RestRequest.enviar("/fs/move", Map.of("from", from, "to", to)));
    }

    @Override
    public void copiar(String from, String to) {
        rest.ejecutarSinRespuesta(RestRequest.enviar("/fs/copy", Map.of("from", from, "to", to)));
    }
}
