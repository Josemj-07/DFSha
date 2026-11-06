package co.edu.upb.dfsha.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.upb.dfsha.client.metadata.Allocation;
import co.edu.upb.dfsha.client.metadata.CommitRequest;
import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.KeepaliveResult;
import co.edu.upb.dfsha.client.metadata.LockGateway;
import co.edu.upb.dfsha.client.metadata.LockRequest;
import co.edu.upb.dfsha.client.metadata.OpenRequest;
import co.edu.upb.dfsha.client.metadata.OpenResult;
import co.edu.upb.dfsha.client.metadata.ReplicaReport;
import co.edu.upb.dfsha.client.sdk.error.ConflictException;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import co.edu.upb.dfsha.client.sdk.model.LockHandle;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LeaseKeeperTest {

    private volatile DfsException keepaliveFailure;
    private final CountDownLatch renewedLock = new CountDownLatch(1);

    private final FileSessionGateway files = new FileSessionGateway() {
        @Override
        public KeepaliveResult mantenerActivo(String handle) {
            if (keepaliveFailure != null) {
                throw keepaliveFailure;
            }
            return new KeepaliveResult(30000, "token-nuevo");
        }

        @Override
        public OpenResult abrir(OpenRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Allocation asignar(String handle, int firstIdx, int count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Allocation recuperarBloque(String handle, int idx, List<String> failedNodes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long confirmar(String handle, CommitRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cerrar(String handle) {
        }

        @Override
        public void reportarReplica(ReplicaReport report) {
        }
    };

    private final LockGateway locks = new LockGateway() {
        @Override
        public LockHandle bloquear(LockRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void renovarBloqueo(String lockId) {
            renewedLock.countDown();
        }

        @Override
        public void desbloquear(String lockId) {
        }
    };

    private static final class Listener implements LeaseKeeper.HandleListener {
        final CountDownLatch token = new CountDownLatch(1);
        final CountDownLatch lost = new CountDownLatch(1);
        final AtomicReference<String> value = new AtomicReference<>();

        @Override
        public void actualizarTokenLectura(String readToken) {
            value.set(readToken);
            token.countDown();
        }

        @Override
        public void notificarPerdida(DfsException cause) {
            lost.countDown();
        }
    }

    private LeaseKeeper crear() {
        return new LeaseKeeper(files, locks, Duration.ofMillis(10), message -> { });
    }

    @Test
    void entregaElTokenDeLecturaRenovado() throws InterruptedException {
        Listener listener = new Listener();
        try (LeaseKeeper keeper = crear()) {
            keeper.registrarHandle("h", listener);
            assertTrue(listener.token.await(5, TimeUnit.SECONDS));
            assertEquals("token-nuevo", listener.value.get());
        }
    }

    @Test
    void avisaSiElServidorDioPorPerdidoElHandle() throws InterruptedException {
        keepaliveFailure = new ConflictException("HANDLE_EXPIRED", "vencido");
        Listener listener = new Listener();
        try (LeaseKeeper keeper = crear()) {
            keeper.registrarHandle("h", listener);
            assertTrue(listener.lost.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unaCaidaDelServidorNoPierdeElHandle() throws InterruptedException {
        keepaliveFailure = new UnavailableException("SERVER_UNREACHABLE", "caído");
        Listener listener = new Listener();
        try (LeaseKeeper keeper = crear()) {
            keeper.registrarHandle("h", listener);
            assertEquals(false, listener.lost.await(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void renuevaLocks() throws InterruptedException {
        try (LeaseKeeper keeper = crear()) {
            keeper.registrarBloqueo("l1");
            assertTrue(renewedLock.await(5, TimeUnit.SECONDS));
        }
    }
}
