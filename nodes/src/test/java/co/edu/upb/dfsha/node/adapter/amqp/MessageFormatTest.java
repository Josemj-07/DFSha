package co.edu.upb.dfsha.node.adapter.amqp;

import co.edu.upb.dfsha.node.config.AmqpConfig;
import co.edu.upb.dfsha.node.domain.BlockEvent;
import co.edu.upb.dfsha.node.domain.BlockReport;
import co.edu.upb.dfsha.node.domain.Heartbeat;
import co.edu.upb.dfsha.node.domain.NodeRegistration;
import co.edu.upb.dfsha.node.domain.ReportedBlock;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El servidor lee cada campo con required() y exige números enteros no negativos
 * (NodeEventListener). Esta prueba convierte los mensajes con el mismo conversor que usa el nodo
 * y comprueba que tengan exactamente los campos de la sección 4.3 del README.
 */
class MessageFormatTest {

    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final MessageConverter conversor = new AmqpConfig().conversorMensajes();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void heartbeat() {
        JsonNode cuerpo = convertir(new Heartbeat("dn-1", 1000, 400, 2, 7, "2026-11-08T06:00:00Z"));

        assertCampos(cuerpo, "nodeId", "capacityBytes", "usedBytes", "activeConnections", "blockCount", "sentAt");
        assertEnteros(cuerpo, "usedBytes", "activeConnections");
    }

    @Test
    void registro() {
        JsonNode cuerpo = convertir(new NodeRegistration("dn-1", "10.0.1.21", 50051, 50052, "az-a", 1000, "PEM"));

        assertCampos(cuerpo, "nodeId", "host", "clientPort", "replicationPort", "az", "capacityBytes", "certPem");
        assertEnteros(cuerpo, "clientPort", "replicationPort", "capacityBytes");
    }

    @Test
    void reporte() {
        JsonNode cuerpo = convertir(new BlockReport("dn-1", List.of(new ReportedBlock("blk_01", 1, 10, SHA))));

        assertCampos(cuerpo, "nodeId", "blocks");
        assertCampos(cuerpo.get("blocks").get(0), "blockId", "gen", "length", "sha256");
        assertEnteros(cuerpo.get("blocks").get(0), "gen", "length");
    }

    @Test
    void eventoDeBloque() {
        JsonNode cuerpo = convertir(new BlockEvent("e-1", "dn-1", "blk_01", 1, 10, SHA));

        assertCampos(cuerpo, "eventId", "nodeId", "blockId", "gen", "length", "sha256");
        assertEnteros(cuerpo, "gen", "length");
    }

    private JsonNode convertir(Object mensaje) {
        return json.readTree(conversor.toMessage(mensaje, new MessageProperties()).getBody());
    }

    private static void assertCampos(JsonNode cuerpo, String... campos) {
        assertEquals(campos.length, cuerpo.size(), "cantidad de campos en " + cuerpo);
        for (String campo : campos) {
            assertTrue(cuerpo.has(campo), "falta el campo " + campo + " en " + cuerpo);
        }
    }

    private static void assertEnteros(JsonNode cuerpo, String... campos) {
        for (String campo : campos) {
            assertTrue(cuerpo.get(campo).isIntegralNumber(), campo + " debe ser un número entero");
        }
    }
}
