package co.edu.upb.dfsha.node.adapter.amqp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxStoreTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @TempDir
    Path dir;

    @Test
    void losEventosSobrevivenAUnReinicioYConservanElOrden() {
        var outbox = new OutboxStore(dir, json);
        outbox.agregar(new OutboxEntry("e-1", "block.received.dn-1", "{\"a\":1}"));
        outbox.agregar(new OutboxEntry("e-2", "block.deleted.dn-1", "{\"b\":2}"));

        var despuesDeReiniciar = new OutboxStore(dir, json);

        assertEquals(List.of("e-1", "e-2"), ids(despuesDeReiniciar));
        assertEquals("{\"a\":1}", despuesDeReiniciar.listar().getFirst().payload());
    }

    @Test
    void unEventoConfirmadoSeBorraTambienDelDisco() {
        var outbox = new OutboxStore(dir, json);
        outbox.agregar(new OutboxEntry("e-1", "block.received.dn-1", "{}"));
        outbox.agregar(new OutboxEntry("e-2", "block.received.dn-1", "{}"));

        outbox.confirmar("e-1");

        assertEquals(List.of("e-2"), ids(outbox));
        assertEquals(List.of("e-2"), ids(new OutboxStore(dir, json)));
    }

    @Test
    void unaLineaCortadaPorUnaCaidaSeDescartaSinPerderLasDemas() throws IOException {
        var outbox = new OutboxStore(dir, json);
        outbox.agregar(new OutboxEntry("e-1", "block.received.dn-1", "{}"));
        Files.writeString(dir.resolve("events.jsonl"), "{\"id\":\"e-2\",\"rout", StandardOpenOption.APPEND);

        var recuperado = new OutboxStore(dir, json);
        recuperado.agregar(new OutboxEntry("e-3", "block.received.dn-1", "{}"));

        assertEquals(List.of("e-1", "e-3"), ids(new OutboxStore(dir, json)));
    }

    @Test
    void sinArchivoEmpiezaVacio() {
        assertTrue(new OutboxStore(dir.resolve("nuevo"), json).listar().isEmpty());
    }

    private static List<String> ids(OutboxStore outbox) {
        return outbox.listar().stream().map(OutboxEntry::id).toList();
    }
}
