package co.edu.upb.dfsha.server.locks.application;

import java.util.concurrent.*;

final class LockWaitQueue {
  private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<LockWaiter>> queues =
      new ConcurrentHashMap<>();

  LockWaiter anotar(long inode) {
    var waiter = new LockWaiter();
    queues.compute(
        inode,
        (id, queue) -> {
          if (queue == null) queue = new ConcurrentLinkedQueue<>();
          queue.add(waiter);
          return queue;
        });
    return waiter;
  }

  boolean permitir(long inode, LockWaiter waiter) {
    var queue = queues.get(inode);
    return queue == null || queue.peek() == waiter;
  }

  void retirar(long inode, LockWaiter waiter) {
    queues.computeIfPresent(
        inode,
        (id, queue) -> {
          queue.remove(waiter);
          return queue.isEmpty() ? null : queue;
        });
    despertar(inode);
  }

  void despertar(long inode) {
    var queue = queues.get(inode);
    var head = queue == null ? null : queue.peek();
    if (head != null) head.despertar();
  }

  void despertarTodos() {
    queues.keySet().forEach(this::despertar);
  }
}
