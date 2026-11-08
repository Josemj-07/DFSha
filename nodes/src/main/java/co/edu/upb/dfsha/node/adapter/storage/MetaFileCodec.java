package co.edu.upb.dfsha.node.adapter.storage;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee y escribe el JSON de los archivos .meta. El formato es fijo y todos los valores ya vienen
 * validados por BlockKey y BlockMeta, así que no hace falta una librería JSON ni escapar texto.
 */
public final class MetaFileCodec {

    private static final Pattern FORMATO = Pattern.compile(
            "^\\{\"blockId\":\"([^\"]+)\",\"gen\":(\\d+),\"length\":(\\d+),\"sha256\":\"([0-9a-f]{64})\"}\\s*$");

    public String escribir(BlockMeta meta) {
        return "{\"blockId\":\"" + meta.key().blockId() + "\",\"gen\":" + meta.key().gen()
                + ",\"length\":" + meta.length() + ",\"sha256\":\"" + meta.sha256() + "\"}\n";
    }

    /** Vacío si el contenido está dañado o no corresponde a un bloque válido. */
    public Optional<BlockMeta> leer(String contenido) {
        Matcher m = FORMATO.matcher(contenido);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            var key = new BlockKey(m.group(1), Long.parseLong(m.group(2)));
            return Optional.of(new BlockMeta(key, Long.parseLong(m.group(3)), m.group(4)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
