package co.edu.upb.dfsha.server.locks.application;

import static org.junit.jupiter.api.Assertions.*;

import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.locks.domain.*;
import co.edu.upb.dfsha.server.support.*;
import org.junit.jupiter.api.*;

class LockManagerTest {
  final MutableClock clock = new MutableClock();
  final MemoryStore store = new MemoryStore(clock);
  final LockManager locks = new LockManager(store, clock);

  @Test
  void permitirRangosDisjuntos() {
    locks.adquirir(1, "a", "EXCLUSIVE", new BlockRange(0, 0), 30000, 0);
    assertNotNull(locks.adquirir(1, "b", "EXCLUSIVE", new BlockRange(1, 1), 30000, 0));
  }

  @Test
  void rechazarSolapados() {
    locks.adquirir(1, "a", "EXCLUSIVE", BlockRange.completo(), 30000, 0);
    assertThrows(
        DfsException.class, () -> locks.adquirir(1, "b", "SHARED", new BlockRange(1, 1), 30000, 0));
  }

  @Test
  void permitirLectoresCompartidos() {
    locks.adquirir(1, "a", "SHARED", BlockRange.completo(), 30000, 0);
    assertNotNull(locks.adquirir(1, "b", "SHARED", BlockRange.completo(), 30000, 0));
  }

  @Test
  void expirarYRechazarFencingAnterior() {
    var old = locks.adquirir(1, "a", "EXCLUSIVE", BlockRange.completo(), 30000, 0);
    clock.avanzar(31);
    locks.limpiar();
    var next = locks.adquirir(1, "b", "EXCLUSIVE", BlockRange.completo(), 30000, 0);
    assertTrue(Long.parseLong(next.fencingToken()) > Long.parseLong(old.fencingToken()));
    assertThrows(
        DfsException.class,
        () ->
            store.leer(
                s -> {
                  locks.verificar(s, old.fencingToken(), 1, "a", new BlockRange(0, 0));
                  return null;
                }));
  }

  @Test
  void renovarSoloPropietario() {
    var held = locks.adquirir(1, "a", "EXCLUSIVE", BlockRange.completo(), 30000, 0);
    assertThrows(DfsException.class, () -> locks.renovar(held.id(), "b"));
    clock.avanzar(20);
    locks.renovar(held.id(), "a");
    clock.avanzar(20);
    assertDoesNotThrow(
        () ->
            store.leer(
                s -> {
                  locks.verificar(s, held.fencingToken(), 1, "a", BlockRange.completo());
                  return null;
                }));
  }

  @Test
  void respetarOrdenFifo() throws Exception {
    var initial = locks.adquirir(1, "initial", "EXCLUSIVE", BlockRange.completo(), 30000, 0);
    var results = new java.util.concurrent.CopyOnWriteArrayList<String>();
    Runnable first =
        () -> {
          var lock = locks.adquirir(1, "first", "EXCLUSIVE", BlockRange.completo(), 30000, 5000);
          results.add("first");
          locks.liberar(lock.id(), "first");
        };
    Runnable second =
        () -> {
          var lock = locks.adquirir(1, "second", "EXCLUSIVE", BlockRange.completo(), 30000, 5000);
          results.add("second");
          locks.liberar(lock.id(), "second");
        };
    Thread a = Thread.ofVirtual().start(first);
    esperarCola(a);
    Thread b = Thread.ofVirtual().start(second);
    esperarCola(b);
    locks.liberar(initial.id(), "initial");
    a.join(5000);
    b.join(5000);
    assertEquals(java.util.List.of("first", "second"), results);
  }

  private void esperarCola(Thread thread) {
    assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(2),
        () -> {
          while (thread.getState() != Thread.State.TIMED_WAITING
              && thread.getState() != Thread.State.WAITING) Thread.yield();
        });
  }
}
