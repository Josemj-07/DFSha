package co.edu.upb.dfsha.node.adapter.amqp;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.DeleteBlockCommand;
import co.edu.upb.dfsha.node.domain.NodeCommand;
import co.edu.upb.dfsha.node.domain.ReplicateBlockCommand;
import co.edu.upb.dfsha.node.domain.ResyncCommand;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte el JSON de un comando en un objeto del dominio, validando cada campo: aunque venga
 * del servidor, todo lo que llega por la red se valida (README, sección 13.4).
 */
public class CommandParser {

    private final JsonMapper json;

    public CommandParser(JsonMapper json) {
        this.json = json;
    }

    /** Lanza IllegalArgumentException si el comando está mal formado o es desconocido. */
    public NodeCommand interpretar(byte[] cuerpo) {
        JsonNode nodo;
        try {
            nodo = json.readTree(cuerpo);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("comando que no es JSON", e);
        }
        String commandId = texto(nodo, "commandId");
        return switch (texto(nodo, "type")) {
            case "REPLICATE_BLOCK" -> leerReplicacion(commandId, nodo);
            case "DELETE_BLOCK" -> new DeleteBlockCommand(commandId, leerBloque(nodo));
            case "RESYNC" -> new ResyncCommand(commandId);
            default -> throw new IllegalArgumentException("tipo de comando desconocido");
        };
    }

    private ReplicateBlockCommand leerReplicacion(String commandId, JsonNode nodo) {
        JsonNode destino = nodo.get("target");
        if (destino == null || !destino.isObject()) {
            throw new IllegalArgumentException("falta target");
        }
        return new ReplicateBlockCommand(commandId, leerBloque(nodo), texto(destino, "nodeId"),
                texto(destino, "addr"), texto(nodo, "token"));
    }

    private static BlockKey leerBloque(JsonNode nodo) {
        JsonNode gen = nodo.get("gen");
        if (gen == null || !gen.isIntegralNumber()) {
            throw new IllegalArgumentException("gen inválida");
        }
        return new BlockKey(texto(nodo, "blockId"), gen.asLong());
    }

    private static String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        if (valor == null || valor.isNull() || valor.isObject() || valor.isArray() || valor.asText().isBlank()) {
            throw new IllegalArgumentException("falta " + campo);
        }
        return valor.asText();
    }
}
