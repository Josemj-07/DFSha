package co.edu.upb.dfsha.client.sdk.model;

/** Atributos de un archivo o directorio. */
public record FileInfo(String name, String path, String type, String owner, String mode,
                       long size, long version, String mtime) {

    public boolean esDirectorio() {
        return "DIR".equals(type);
    }
}
