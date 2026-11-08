package co.edu.upb.dfsha.node.adapter.amqp;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.DeleteBlockCommand;
import co.edu.upb.dfsha.node.domain.ReplicateBlockCommand;
import co.edu.upb.dfsha.node.domain.ResyncCommand;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Los JSON de estas pruebas son los que producen los records ReplicateBlock, DeleteBlock y Resync del servidor. */
class CommandParserTest {

    private final CommandParser parser = new CommandParser(JsonMapper.builder().build());

    @Test
    void replicarBloque() {
        var comando = parser.interpretar(bytes("""
                {"commandId":"c-7f3a","type":"REPLICATE_BLOCK","blockId":"blk_01","gen":1,
                 "target":{"nodeId":"dn-3","addr":"10.0.3.14:50052"},"token":"eyJ.abc"}"""));

        assertEquals(new ReplicateBlockCommand("c-7f3a", new BlockKey("blk_01", 1), "dn-3", "10.0.3.14:50052",
                "eyJ.abc"), comando);
    }

    @Test
    void borrarBloque() {
        var comando = parser.interpretar(bytes("""
                {"commandId":"c-7f3b","type":"DELETE_BLOCK","blockId":"blk_01","gen":2}"""));

        assertEquals(new DeleteBlockCommand("c-7f3b", new BlockKey("blk_01", 2)), comando);
    }

    @Test
    void resync() {
        assertEquals(new ResyncCommand("c-7f3c"), parser.interpretar(bytes("""
                {"commandId":"c-7f3c","type":"RESYNC"}""")));
    }

    @Test
    void rechazaComandosMalFormados() {
        rechaza("no es json");
        rechaza("{\"commandId\":\"c-1\",\"type\":\"FORMATEAR_DISCO\"}");
        rechaza("{\"type\":\"RESYNC\"}");
        rechaza("{\"commandId\":\"c-1\",\"type\":\"DELETE_BLOCK\",\"blockId\":\"blk_01\",\"gen\":\"uno\"}");
        rechaza("{\"commandId\":\"c-1\",\"type\":\"REPLICATE_BLOCK\",\"blockId\":\"blk_01\",\"gen\":1,\"token\":\"t\"}");
    }

    @Test
    void rechazaUnBlockIdQuePodriaSalirseDelAlmacen() {
        rechaza("{\"commandId\":\"c-1\",\"type\":\"DELETE_BLOCK\",\"blockId\":\"../../etc/passwd\",\"gen\":1}");
    }

    private void rechaza(String json) {
        assertThrows(IllegalArgumentException.class, () -> parser.interpretar(bytes(json)), json);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
