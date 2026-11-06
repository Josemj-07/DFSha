package co.edu.upb.dfsha.client.metadata;

/** Solicitud de apertura de un archivo. */
public record OpenRequest(String path, String mode, boolean create, boolean exclusive) {
}
