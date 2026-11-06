package co.edu.upb.dfsha.client.integrity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FileHashTest {

    private static final String HOLA = "b221d9dbb083a7f33428d7c2a3c3198ae925614d70210e28716ccaa7cd4ddb79";
    private static final String MUNDO = "cb9c245f6cf4910aca447a02e910139b5456d63d53be538e386ed48472eaca5f";

    /** Vector compartido con el servidor: bloques "hola" y "mundo", en ese orden. */
    private static final String VECTOR = "c030ed83e4b337813e905c456a45c002790bc315b88ec9d47962b8563d502803";

    @Test
    void cumpleElVectorCompartido() {
        FileHash hash = new FileHash();
        hash.agregarBloque(0, HOLA);
        hash.agregarBloque(1, MUNDO);
        assertEquals(VECTOR, hash.calcular());
    }

    @Test
    void elOrdenDeLlegadaNoImporta() {
        FileHash hash = new FileHash();
        hash.agregarBloque(1, MUNDO);
        hash.agregarBloque(0, HOLA);
        assertEquals(VECTOR, hash.calcular());
    }

    @Test
    void unArchivoVacioEsElHashDeNada() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", new FileHash().calcular());
    }
}
