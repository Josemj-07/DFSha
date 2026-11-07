package co.edu.upb.dfsha.server.journal.infra;

import co.edu.upb.dfsha.server.journal.domain.*;
import co.edu.upb.dfsha.server.journal.op.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;
import tools.jackson.databind.json.JsonMapper;

public class FileEditLog implements AutoCloseable {
  private final Path directory;
  private final JsonMapper mapper;
  private FileChannel channel;
  private long lsn;
  private long lastSeenLsn;
  private boolean failed;

  public record Entry(long lsn, tools.jackson.databind.JsonNode op, long crc) {}

  public record Snapshot(long lsn, MetadataState state) {}

  public FileEditLog(Path directory, JsonMapper mapper) throws IOException {
    this.directory = directory;
    this.mapper = mapper.rebuild().addMixIn(JournalOp.class, JournalOpMixin.class).build();
    Files.createDirectories(directory);
  }

  public MetadataState recuperar() throws IOException {
    MetadataState state = new MetadataState();
    List<Path> snapshots = listar("snapshot-");
    for (Path path : snapshots.reversed())
      try {
        Snapshot snapshot = mapper.readValue(Files.readAllBytes(path), Snapshot.class);
        state = snapshot.state();
        lsn = snapshot.lsn();
        break;
      } catch (RuntimeException | IOException ignored) {
        /* Se intenta el snapshot anterior. */
      }
    List<Path> logs = listar("edits-");
    for (int i = 0; i < logs.size(); i++) reproducir(logs.get(i), state, i == logs.size() - 1);
    channel =
        FileChannel.open(
            directory.resolve("edits-" + (lsn + 1) + ".log"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND);
    sincronizarDirectorio();
    return state;
  }

  private List<Path> listar(String prefix) throws IOException {
    try (var files = Files.list(directory)) {
      return files
          .filter(
              p -> p.getFileName().toString().startsWith(prefix) && !p.toString().endsWith(".tmp"))
          .sorted(
              Comparator.comparingLong(
                  p -> Long.parseLong(p.getFileName().toString().split("[-.]")[1])))
          .toList();
    }
  }

  private void reproducir(Path path, MetadataState state, boolean lastFile) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    int start = 0;
    for (int i = 0; i <= bytes.length; i++) {
      if (i < bytes.length && bytes[i] != '\n') continue;
      if (i == start) continue;
      boolean recoverable = false;
      try {
        if (i == bytes.length) {
          recoverable = true;
          throw new IOException("Entrada incompleta");
        }
        Entry entry = mapper.readValue(Arrays.copyOfRange(bytes, start, i), Entry.class);
        if (calcularCrc(entry.op().toString()) != entry.crc()) {
          recoverable = true;
          throw new IOException("CRC invalido");
        }
        if (entry.lsn() != lastSeenLsn + 1) throw new IllegalStateException("LSN no consecutivo");
        lastSeenLsn = entry.lsn();
        if (entry.lsn() > lsn) {
          if (entry.lsn() != lsn + 1) throw new IllegalStateException("LSN no consecutivo");
          mapper.treeToValue(entry.op(), JournalOp.class).aplicar(state);
          lsn = entry.lsn();
        }
      } catch (IllegalStateException ex) {
        throw new IOException("LSN invalido en " + path, ex);
      } catch (Exception ex) {
        if (!recoverable || !lastFile || i < bytes.length - 1)
          throw new IOException("WAL corrupto en " + path + ":" + start, ex);
        try (var file = FileChannel.open(path, StandardOpenOption.WRITE)) {
          file.truncate(start);
          file.force(true);
        }
        return;
      }
      start = i + 1;
    }
  }

  public void anexar(StateChange change) throws IOException {
    anexar(new MetadataChange(change));
  }

  public void anexar(JournalOp op) throws IOException {
    if (failed)
      throw new IOException("WAL bloqueado tras un fallo de escritura; reinicie para recuperar");
    try {
      String json = mapper.writerFor(JournalOp.class).writeValueAsString(op);
      byte[] bytes =
          (mapper.writeValueAsString(new Entry(lsn + 1, mapper.readTree(json), calcularCrc(json)))
                  + "\n")
              .getBytes(StandardCharsets.UTF_8);
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(false);
      lsn++;
    } catch (IOException ex) {
      failed = true;
      throw ex;
    }
  }

  private long calcularCrc(String op) {
    var crc = new CRC32C();
    crc.update(op.getBytes(StandardCharsets.UTF_8));
    return crc.getValue();
  }

  public synchronized void guardarSnapshot(MetadataState state) throws IOException {
    Path temp = directory.resolve("snapshot-" + lsn + ".json.tmp"),
        target = directory.resolve("snapshot-" + lsn + ".json");
    byte[] bytes = mapper.writeValueAsBytes(new Snapshot(lsn, state));
    try (var file =
        FileChannel.open(
            temp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) file.write(buffer);
      file.force(true);
    }
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) {
      dir.force(true);
    }
    channel.close();
    channel =
        FileChannel.open(
            directory.resolve("edits-" + (lsn + 1) + ".log"),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
    sincronizarDirectorio();
    var snapshots = listar("snapshot-");
    for (int i = 0; i < snapshots.size() - 2; i++) Files.delete(snapshots.get(i));
  }

  private void sincronizarDirectorio() throws IOException {
    try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) {
      dir.force(true);
    }
  }

  @Override
  public void close() throws IOException {
    if (channel != null) channel.close();
  }
}
