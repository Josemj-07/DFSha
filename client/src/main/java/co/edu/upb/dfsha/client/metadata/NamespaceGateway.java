package co.edu.upb.dfsha.client.metadata;

import co.edu.upb.dfsha.client.sdk.model.FileInfo;

/** Operaciones del espacio de nombres. */
public interface NamespaceGateway {

    DirectoryPage listar(String path, String cursor);

    FileInfo obtenerAtributos(String path);

    void crearDirectorio(String path, boolean parents);

    void borrarDirectorio(String path, boolean recursive);

    void borrar(String path);

    void mover(String from, String to);

    void copiar(String from, String to);
}
