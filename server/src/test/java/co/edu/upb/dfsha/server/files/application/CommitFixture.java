package co.edu.upb.dfsha.server.files.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.edu.upb.dfsha.server.audit.application.AuditService;
import co.edu.upb.dfsha.server.auth.application.CurrentUser;
import co.edu.upb.dfsha.server.auth.domain.User;
import co.edu.upb.dfsha.server.blocks.application.ReceiptVerifier;
import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.cluster.application.ClusterState;
import co.edu.upb.dfsha.server.cluster.domain.Node;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.config.DfsProperties;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.locks.application.LockManager;
import co.edu.upb.dfsha.server.locks.domain.BlockRange;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import co.edu.upb.dfsha.server.support.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;

abstract class CommitFixture {
  final MutableClock clock = new MutableClock();
  final MemoryStore store = new MemoryStore(clock);
  final CurrentUser user = mock(CurrentUser.class);
  final ClusterState cluster = new ClusterState();
  final LockManager locks = new LockManager(store, clock);
  final DfsProperties props = new DfsProperties(null, null, 4, "password-1234");
  CommitService service;
  String token;
  Map<String, KeyPair> keys = new HashMap<>();

  @BeforeEach
  void preparar() throws Exception {
    when(user.obtenerNombre()).thenReturn("ana");
    for (String id : List.of("a", "b")) {
      var generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(256);
      var pair = generator.generateKeyPair();
      keys.put(id, pair);
      cluster.registrar(
          new Node(
              id,
              id,
              50051,
              50052,
              id,
              100,
              0,
              0,
              clock.instant(),
              "ALIVE",
              pair.getPublic(),
              true));
    }
    store.mutar(
        s -> {
          s.users.put("ana", new User("ana", "hash", "USER", 100));
          co.edu.upb.dfsha.server.support.TestNamespace.crearHome(s, "ana", clock);
          s.directories.poner("/home/ana/f", 1L);
          s.inodes.put(
              1L,
              new Inode(
                  1,
                  "ana",
                  "rw-------",
                  false,
                  0,
                  0,
                  null,
                  clock.instant(),
                  clock.instant(),
                  List.of()));
          token =
              locks.conceder(s, 1, "ana", "EXCLUSIVE", BlockRange.completo(), 30000).fencingToken();
          s.handles.put(
              "h",
              new Handle(
                  "h",
                  "ana",
                  1,
                  "w",
                  0,
                  List.of(),
                  null,
                  clock.instant().plusSeconds(30),
                  Map.of(0, new Block(0, "blk", 1, 0, null)),
                  null,
                  null));
          return null;
        });
    service =
        new CommitService(
            store,
            new HandleAccess(clock, user),
            locks,
            new CommitValidator(cluster, new ReceiptVerifier(), props),
            clock,
            new AuditService(clock));
  }

  String calcularHash() {
    return co.edu.upb.dfsha.server.auth.application.AuthService.calcularHash("hola");
  }

  Receipt firmar(String node, long gen) throws Exception {
    var signature = Signature.getInstance("SHA256withECDSA");
    signature.initSign(keys.get(node).getPrivate());
    signature.update(
        ("dfsha-receipt-v1|blk|" + gen + "|4|" + calcularHash() + "|" + node)
            .getBytes(StandardCharsets.UTF_8));
    return new Receipt(node, Base64.getEncoder().encodeToString(signature.sign()));
  }

  CommitRequest crear() throws Exception {
    return new CommitRequest(
        token,
        4,
        CommitValidator.calcularHash(List.of(new Block(0, "blk", 1, 4, calcularHash()))),
        List.of(
            new CommittedBlock(
                0, "blk", 1, 4, calcularHash(), List.of(firmar("a", 1), firmar("b", 1)))));
  }

  void comprobarCodigo(String code, CommitRequest request) {
    var error = assertThrows(DfsException.class, () -> service.confirmar("h", request));
    assertEquals(code, error.obtenerCodigo());
    assertEquals(0L, store.<Long>leer(s -> s.inodes.get(1L).version()).longValue());
  }
}
