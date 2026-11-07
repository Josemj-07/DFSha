package co.edu.upb.dfsha.server.journal.infra;

import static org.junit.jupiter.api.Assertions.*;

import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.config.DfsProperties;
import co.edu.upb.dfsha.server.locks.application.LockManager;
import co.edu.upb.dfsha.server.locks.domain.BlockRange;
import co.edu.upb.dfsha.server.support.MutableClock;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DurableMetadataStoreTest {
  @TempDir Path dir;
  final MutableClock clock = new MutableClock();

  DurableMetadataStore abrir() throws Exception {
    return new DurableMetadataStore(
        new DfsProperties(dir, dir, 4, "password"),
        JsonMapper.builder().build(),
        clock,
        (before, after) -> {});
  }

  @Test
  void noAplicarCambioSiFallaDisco() throws Exception {
    var store = abrir();
    store.cerrar();
    assertThrows(
        DfsException.class,
        () ->
            store.mutar(
                s -> {
                  s.fencingSequence = 123;
                  return null;
                }));
    assertEquals(0L, store.<Long>leer(s -> s.fencingSequence).longValue());
  }

  @Test
  void conservarFencingTrasReiniciar() throws Exception {
    var first = abrir();
    var manager = new LockManager(first, clock);
    String token =
        manager.adquirir(1, "ana", "EXCLUSIVE", BlockRange.completo(), 30000, 0).fencingToken();
    first.cerrar();
    var recovered = abrir();
    var next = new LockManager(recovered, clock);
    next.liberar(recovered.leer(s -> s.locks.values().iterator().next().id()), "ana");
    assertTrue(
        Long.parseLong(
                next.adquirir(1, "ana", "EXCLUSIVE", BlockRange.completo(), 30000, 0)
                    .fencingToken())
            > Long.parseLong(token));
    recovered.cerrar();
  }

  @Test
  void noModificarEstadoDesdeConsulta() throws Exception {
    var store = abrir();
    store.leer(
        s -> {
          s.fencingSequence = 99;
          return null;
        });
    assertEquals(0L, store.<Long>leer(s -> s.fencingSequence).longValue());
    store.cerrar();
  }
}
