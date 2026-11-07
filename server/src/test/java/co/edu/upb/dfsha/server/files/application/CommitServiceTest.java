package co.edu.upb.dfsha.server.files.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.edu.upb.dfsha.server.auth.domain.User;
import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import co.edu.upb.dfsha.server.support.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CommitServiceTest extends CommitFixture {
  @Test
  void publicarYReintentar() throws Exception {
    var request = crear();
    assertEquals(1, service.confirmar("h", request).version());
    assertEquals(1, service.confirmar("h", request).version());
    assertEquals(4L, store.<Long>leer(s -> s.inodes.get(1L).size()).longValue());
  }

  @Test
  void rechazarFencing() throws Exception {
    var r = crear();
    comprobarCodigo(
        "STALE_FENCING_TOKEN", new CommitRequest("999", r.size(), r.fileSha256(), r.blocks()));
  }

  @Test
  void rechazarReciboFalso() throws Exception {
    var r = crear();
    comprobarCodigo(
        "BLOCK_NOT_DURABLE",
        new CommitRequest(
            token,
            4,
            r.fileSha256(),
            List.of(
                new CommittedBlock(
                    0,
                    "blk",
                    1,
                    4,
                    calcularHash(),
                    List.of(firmar("a", 1), new Receipt("b", "AAAA"))))));
  }

  @Test
  void rechazarOtraGeneracion() throws Exception {
    var r = crear();
    comprobarCodigo(
        "BLOCK_NOT_DURABLE",
        new CommitRequest(
            token,
            4,
            r.fileSha256(),
            List.of(
                new CommittedBlock(
                    0, "blk", 1, 4, calcularHash(), List.of(firmar("a", 2), firmar("b", 1))))));
  }

  @Test
  void rechazarTamanoYHash() throws Exception {
    var r = crear();
    comprobarCodigo("BLOCK_NOT_DURABLE", new CommitRequest(token, 3, r.fileSha256(), r.blocks()));
    comprobarCodigo("BLOCK_NOT_DURABLE", new CommitRequest(token, 4, "0".repeat(64), r.blocks()));
  }

  @Test
  void rechazarHandleVencido() throws Exception {
    var r = crear();
    clock.avanzar(31);
    comprobarCodigo("HANDLE_EXPIRED", r);
  }

  @Test
  void rechazarCuota() throws Exception {
    var r = crear();
    store.mutar(
        s -> {
          s.users.put("ana", new User("ana", "hash", "USER", 3));
          return null;
        });
    comprobarCodigo("QUOTA_EXCEEDED", r);
  }

  @Test
  void rechazarCAS() throws Exception {
    var r = crear();
    store.mutar(
        s -> {
          var i = s.inodes.get(1L);
          s.inodes.put(
              1L,
              new Inode(
                  i.id(),
                  i.owner(),
                  i.mode(),
                  false,
                  0,
                  1,
                  null,
                  i.ctime(),
                  i.mtime(),
                  i.blocks()));
          return null;
        });
    assertEquals(
        "VERSION_CONFLICT",
        assertThrows(DfsException.class, () -> service.confirmar("h", r)).obtenerCodigo());
  }

  @Test
  void verificarVectorHash() {
    assertEquals(
        "c030ed83e4b337813e905c456a45c002790bc315b88ec9d47962b8563d502803",
        CommitValidator.calcularHash(
            List.of(
                new Block(0, "a", 1, 4, calcularHash()),
                new Block(
                    1,
                    "b",
                    1,
                    5,
                    co.edu.upb.dfsha.server.auth.application.AuthService.calcularHash("mundo")))));
  }

  @Test
  void reintentarContenidoConRecibosNuevos() throws Exception {
    service.confirmar("h", crear());
    assertEquals(1, service.confirmar("h", crear()).version());
  }

  @Test
  void reintentoNoReviveReplicaCorrupta() throws Exception {
    var request = crear();
    service.confirmar("h", request);
    cluster.quitar("blk", 1, "b", true);
    service.confirmar("h", request);
    assertEquals(1, cluster.obtenerReplicas("blk", 1, true).size());
  }
}
