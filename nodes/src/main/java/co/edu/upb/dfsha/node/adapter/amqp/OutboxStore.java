package co.edu.upb.dfsha.node.adapter.amqp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Cola de eventos en disco (/data/outbox/events.jsonl, una línea JSON por evento). Un evento se
 * guarda con fsync antes de intentar enviarlo y solo se borra cuando RabbitMQ lo confirma. Así
 * no se pierde aunque el broker esté caído o el nodo se reinicie.
 */
public class OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(OutboxStore.class);

    private final Path directorio;
    private final Path archivo;
    private final JsonMapper json;
    private final List<OutboxEntry> pendientes = new ArrayList<>();

    public OutboxStore(Path directorio, JsonMapper json) {
        this.directorio = directorio;
        this.archivo = directorio.resolve("events.jsonl");
        this.json = json;
        try {
            Files.createDirectories(directorio);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        cargar();
    }

    public synchronized void agregar(OutboxEntry entrada) {
        String linea = json.writeValueAsString(entrada) + "\n";
        try (var canal = FileChannel.open(archivo, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            canal.write(ByteBuffer.wrap(linea.getBytes(StandardCharsets.UTF_8)));
            canal.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("no se pudo guardar el evento en el outbox", e);
        }
        pendientes.add(entrada);
    }

    /** Eventos pendientes, en el orden en que se agregaron. */
    public synchronized List<OutboxEntry> listar() {
        return List.copyOf(pendientes);
    }

    public synchronized void confirmar(String id) {
        if (pendientes.removeIf(entrada -> entrada.id().equals(id))) {
            reescribir();
        }
    }

    private void cargar() {
        if (!Files.exists(archivo)) {
            return;
        }
        boolean daniado = false;
        try {
            for (String linea : Files.readAllLines(archivo, StandardCharsets.UTF_8)) {
                if (!linea.isBlank()) {
                    daniado |= !interpretar(linea);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (daniado) {
            // Una línea cortada por una caída se mezclaría con la siguiente que se agregue.
            reescribir();
        }
        log.info("Outbox cargado: {} eventos pendientes", pendientes.size());
    }

    private boolean interpretar(String linea) {
        try {
            pendientes.add(json.readValue(linea, OutboxEntry.class));
            return true;
        } catch (JacksonException e) {
            log.warn("Línea dañada en el outbox descartada (posible caída durante una escritura)");
            return false;
        }
    }

    /** Escribe una copia nueva y la reemplaza de forma atómica: nunca queda un archivo a medias. */
    private void reescribir() {
        Path temporal = directorio.resolve("events.jsonl.tmp");
        var contenido = new StringBuilder();
        for (OutboxEntry entrada : pendientes) {
            contenido.append(json.writeValueAsString(entrada)).append('\n');
        }
        try {
            try (var canal = FileChannel.open(temporal, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                canal.write(ByteBuffer.wrap(contenido.toString().getBytes(StandardCharsets.UTF_8)));
                canal.force(true);
            }
            Files.move(temporal, archivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var dir = FileChannel.open(directorio, StandardOpenOption.READ)) {
                dir.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("no se pudo actualizar el outbox", e);
        }
    }
}
