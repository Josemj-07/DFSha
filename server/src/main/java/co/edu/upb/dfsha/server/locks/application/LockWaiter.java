package co.edu.upb.dfsha.server.locks.application;

import java.util.concurrent.CompletableFuture;

final class LockWaiter {
  private volatile CompletableFuture<Void> ready = new CompletableFuture<>();

  CompletableFuture<Void> preparar() {
    ready = new CompletableFuture<>();
    return ready;
  }

  void despertar() {
    ready.complete(null);
  }
}
