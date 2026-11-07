package co.edu.upb.dfsha.server.files.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import co.edu.upb.dfsha.server.support.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CommitConcurrencyTest extends CommitFixture {
  private CommittedBlock crearBloque(int idx, String id) throws Exception {
    List<Receipt> receipts = new ArrayList<>();
    for (String node : keys.keySet()) {
      var signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(keys.get(node).getPrivate());
      signer.update(
          ("dfsha-receipt-v1|" + id + "|1|4|" + calcularHash() + "|" + node)
              .getBytes(StandardCharsets.UTF_8));
      receipts.add(new Receipt(node, Base64.getEncoder().encodeToString(signer.sign())));
    }
    return new CommittedBlock(idx, id, 1, 4, calcularHash(), receipts);
  }

  private void configurar(int secondIndex) {
    store.mutar(
        s -> {
          var base =
              List.of(
                  new Block(0, "old0", 1, 4, calcularHash()),
                  new Block(1, "old1", 1, 4, calcularHash()));
          var inode = s.inodes.get(1L);
          s.inodes.put(
              1L,
              new Inode(
                  1,
                  "ana",
                  "rw-------",
                  false,
                  8,
                  0,
                  CommitValidator.calcularHash(base),
                  inode.ctime(),
                  inode.mtime(),
                  base));
          s.handles.put(
              "h",
              new Handle(
                  "h",
                  "ana",
                  1,
                  "rw",
                  0,
                  base,
                  null,
                  clock.instant().plusSeconds(30),
                  Map.of(0, new Block(0, "blk", 1, 0, null)),
                  null,
                  null));
          s.handles.put(
              "h2",
              new Handle(
                  "h2",
                  "ana",
                  1,
                  "rw",
                  0,
                  base,
                  null,
                  clock.instant().plusSeconds(30),
                  Map.of(secondIndex, new Block(secondIndex, "blk2", 1, 0, null)),
                  null,
                  null));
          return null;
        });
  }

  private String intentar(
      String handle, CommitRequest request, java.util.concurrent.CountDownLatch gate)
      throws Exception {
    gate.await();
    try {
      service.confirmar(handle, request);
      return "OK";
    } catch (DfsException ex) {
      return ex.obtenerCodigo();
    }
  }

  @Test
  void publicarRangosDisjuntosSinPerderCambios() throws Exception {
    configurar(1);
    var first = new CommitRequest(token, 0, null, List.of(crearBloque(0, "blk")));
    var second = new CommitRequest(token, 0, null, List.of(crearBloque(1, "blk2")));
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var a = executor.submit(() -> intentar("h", first, gate));
      var b = executor.submit(() -> intentar("h2", second, gate));
      gate.countDown();
      assertEquals("OK", a.get());
      assertEquals("OK", b.get());
    }
    var inode = store.leer(s -> s.inodes.get(1L));
    assertEquals(2, inode.version());
    assertEquals(List.of("blk", "blk2"), inode.blocks().stream().map(Block::blockId).toList());
    assertEquals(8, inode.size());
  }

  @Test
  void rechazarUnoDeDosCommitsSobreMismoIndice() throws Exception {
    configurar(0);
    var first = new CommitRequest(token, 0, null, List.of(crearBloque(0, "blk")));
    var second = new CommitRequest(token, 0, null, List.of(crearBloque(0, "blk2")));
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var a = executor.submit(() -> intentar("h", first, gate));
      var b = executor.submit(() -> intentar("h2", second, gate));
      gate.countDown();
      assertEquals(Set.of("OK", "VERSION_CONFLICT"), Set.of(a.get(), b.get()));
    }
  }
}
