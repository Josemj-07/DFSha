package co.edu.upb.dfsha.node.adapter.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Cuenta las llamadas gRPC en curso. Va en el heartbeat como activeConnections: el servidor lo
 * usa para no asignar más trabajo a un nodo ocupado.
 */
public class ActiveCallsInterceptor implements ServerInterceptor, IntSupplier {

    private final AtomicInteger activas = new AtomicInteger();

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        activas.incrementAndGet();
        AtomicBoolean cerrada = new AtomicBoolean();
        Runnable cerrar = () -> {
            if (cerrada.compareAndSet(false, true)) {
                activas.decrementAndGet();
            }
        };
        ServerCall.Listener<Q> delegado;
        try {
            delegado = next.startCall(call, headers);
        } catch (RuntimeException e) {
            cerrar.run();
            throw e;
        }
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegado) {
            @Override
            public void onComplete() {
                cerrar.run();
                super.onComplete();
            }

            @Override
            public void onCancel() {
                cerrar.run();
                super.onCancel();
            }
        };
    }

    @Override
    public int getAsInt() {
        return activas.get();
    }
}
