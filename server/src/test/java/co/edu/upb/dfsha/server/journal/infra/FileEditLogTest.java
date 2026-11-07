package co.edu.upb.dfsha.server.journal.infra;

import static org.junit.jupiter.api.Assertions.*;

import co.edu.upb.dfsha.server.journal.domain.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class FileEditLogTest {
  @TempDir Path dir;
  final JsonMapper mapper = JsonMapper.builder().build();

  private void agregar(FileEditLog log, MetadataState state, long value) throws Exception {
    MetadataState next = mapper.readValue(mapper.writeValueAsBytes(state), MetadataState.class);
    next.fencingSequence = value;
    StateChange change = StateChange.entre(state, next);
    log.anexar(change);
    change.aplicar(state);
  }

  @Test
  void reproducirEstadoYFencing() throws Exception {
    String expected;
    try (var log = new FileEditLog(dir, mapper)) {
      var s = log.recuperar();
      agregar(log, s, 12);
      var next = mapper.readValue(mapper.writeValueAsBytes(s), MetadataState.class);
      next.directories.poner("/", 1L);
      var change = StateChange.entre(s, next);
      log.anexar(change);
      change.aplicar(s);
      agregar(log, s, 13);
      expected = mapper.writeValueAsString(s);
    }
    try (var log = new FileEditLog(dir, mapper)) {
      assertEquals(expected, mapper.writeValueAsString(log.recuperar()));
    }
  }

  @Test
  void recuperarUltimaLineaTruncada() throws Exception {
    try (var log = new FileEditLog(dir, mapper)) {
      var s = log.recuperar();
      agregar(log, s, 1);
    }
    Path file = dir.resolve("edits-1.log");
    long size = Files.size(file);
    Files.writeString(file, "{incompleto", StandardOpenOption.APPEND);
    try (var log = new FileEditLog(dir, mapper)) {
      assertEquals(1, log.recuperar().fencingSequence);
      assertEquals(size, Files.size(file));
    }
  }

  @Test
  void rechazarCorrupcionIntermedia() throws Exception {
    try (var log = new FileEditLog(dir, mapper)) {
      var s = log.recuperar();
      agregar(log, s, 1);
      agregar(log, s, 2);
    }
    Path file = dir.resolve("edits-1.log");
    var lines = Files.readAllLines(file);
    lines.set(0, lines.getFirst().replace("\"crc\":", "\"crc\":9"));
    Files.write(file, lines);
    try (var log = new FileEditLog(dir, mapper)) {
      assertThrows(java.io.IOException.class, log::recuperar);
    }
  }

  @Test
  void recuperarSnapshotYLog() throws Exception {
    try (var log = new FileEditLog(dir, mapper)) {
      var s = log.recuperar();
      agregar(log, s, 1);
      log.guardarSnapshot(s);
      agregar(log, s, 2);
    }
    try (var log = new FileEditLog(dir, mapper)) {
      assertEquals(2, log.recuperar().fencingSequence);
    }
  }

  @Test
  void usarSnapshotAnteriorSiUltimoDanado() throws Exception {
    try (var log = new FileEditLog(dir, mapper)) {
      var s = log.recuperar();
      agregar(log, s, 1);
      log.guardarSnapshot(s);
      agregar(log, s, 2);
      log.guardarSnapshot(s);
      agregar(log, s, 3);
    }
    Files.writeString(dir.resolve("snapshot-2.json"), "corrupto");
    try (var log = new FileEditLog(dir, mapper)) {
      assertEquals(3, log.recuperar().fencingSequence);
    }
  }

  @Test
  void truncarUltimoCrcInvalido() throws Exception {
    try (var log = new FileEditLog(dir, mapper)) {
      var state = log.recuperar();
      agregar(log, state, 1);
      agregar(log, state, 2);
    }
    Path file = dir.resolve("edits-1.log");
    var lines = Files.readAllLines(file);
    var node = mapper.readTree(lines.getLast());
    ((tools.jackson.databind.node.ObjectNode) node).put("crc", -1);
    lines.set(lines.size() - 1, node.toString());
    Files.write(file, lines);
    try (var log = new FileEditLog(dir, mapper)) {
      assertEquals(1, log.recuperar().fencingSequence);
    }
  }
}
