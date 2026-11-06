package co.edu.upb.dfsha.client.metadata;

/** Entrega el token de sesión con el que se firman las peticiones. */
public interface TokenSource {

    String obtenerToken();

    /** Renueva tras un 401. Devuelve el token nuevo, o el vigente si otro hilo ya lo renovó. */
    String renovarTrasRechazo(String rejectedToken);
}
