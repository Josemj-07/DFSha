package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.port.ReplicaFailure;
import co.edu.upb.dfsha.node.port.ReplicaListener;
import co.edu.upb.dfsha.node.port.ReplicaStream;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Stream gRPC hacia el siguiente nodo. Además vigila que avance: si el siguiente nodo deja de
 * aceptar datos o no confirma a tiempo, se cancela y se le cuenta como nodo fallido.
 */
final class GrpcReplicaStream implements ReplicaStream, ClientResponseObserver<WriteRequest, WriteResponse> {

    private final String nodeId;
    private final ReplicaListener listener;
    private final ScheduledExecutorService scheduler;
    private final long inactividadNanos;
    private final long confirmacionNanos;
    private volatile ClientCallStreamObserver<WriteRequest> salida;
    private volatile long ultimoAvance = System.nanoTime();
    private volatile boolean completado;
    private volatile boolean terminado;
    private ScheduledFuture<?> vigilancia;

    GrpcReplicaStream(String nodeId, ReplicaListener listener, ScheduledExecutorService scheduler,
                      Duration inactividad, Duration confirmacion) {
        this.nodeId = nodeId;
        this.listener = listener;
        this.scheduler = scheduler;
        this.inactividadNanos = inactividad.toNanos();
        this.confirmacionNanos = confirmacion.toNanos();
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<WriteRequest> stream) {
        salida = stream;
        stream.setOnReadyHandler(() -> {
            registrarAvance();
            listener.alEstarListo();
        });
        long periodo = Math.max(1, inactividadNanos / 4);
        vigilancia = scheduler.scheduleWithFixedDelay(this::revisarAvance, periodo, periodo, TimeUnit.NANOSECONDS);
    }

    @Override
    public boolean estaListo() {
        return salida.isReady();
    }

    @Override
    public void enviar(WriteRequest mensaje) {
        salida.onNext(mensaje);
        registrarAvance();
    }

    @Override
    public void completar() {
        completado = true;
        registrarAvance();
        salida.onCompleted();
    }

    @Override
    public void cancelar(String motivo) {
        if (!terminado) {
            detenerVigilancia();
            salida.cancel(motivo, null);
        }
    }

    @Override
    public void onNext(WriteResponse respuesta) {
        registrarAvance();
        listener.alResponder(respuesta);
    }

    @Override
    public void onCompleted() {
        detenerVigilancia();
        listener.alTerminar();
    }

    @Override
    public void onError(Throwable error) {
        detenerVigilancia();
        listener.alFallar(new ReplicaFailure(Status.fromThrowable(error).getCode().name(), identificarFallidos(error)));
    }

    /**
     * Si el siguiente nodo respondió ABORTED, la falla está más adelante y él dice dónde.
     * Cualquier otro error (caído, sin respuesta, datos corruptos) es culpa del siguiente nodo.
     */
    private List<String> identificarFallidos(Throwable error) {
        Status status = Status.fromThrowable(error);
        Metadata trailers = Status.trailersFromThrowable(error);
        String informados = trailers == null ? null : trailers.get(WriteSession.FAILED_NODES);
        if (status.getCode() == Status.Code.ABORTED && informados != null && !informados.isBlank()) {
            return Arrays.asList(informados.split(","));
        }
        return List.of(nodeId);
    }

    // Mientras el siguiente nodo acepta datos, la lentitud es del cliente y no cuenta como falla.
    private void revisarAvance() {
        if (terminado) {
            return;
        }
        long limite;
        if (completado) {
            limite = confirmacionNanos;
        } else if (!salida.isReady()) {
            limite = inactividadNanos;
        } else {
            registrarAvance();
            return;
        }
        if (System.nanoTime() - ultimoAvance > limite) {
            salida.cancel("el nodo " + nodeId + " no avanza", null);
        }
    }

    private void registrarAvance() {
        ultimoAvance = System.nanoTime();
    }

    private void detenerVigilancia() {
        terminado = true;
        if (vigilancia != null) {
            vigilancia.cancel(false);
        }
    }
}
