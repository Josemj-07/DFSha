package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.proto.v1.ReplicationServiceGrpc;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/** Servicio del puerto 50052: recibe bloques de otro nodo de la cadena o de una re-replicación. */
public class ReplicationGrpcService extends ReplicationServiceGrpc.ReplicationServiceImplBase {

    private final WriteSessionFactory sesiones;

    public ReplicationGrpcService(WriteSessionFactory sesiones) {
        this.sesiones = sesiones;
    }

    @Override
    public StreamObserver<WriteRequest> replicarBloque(StreamObserver<WriteResponse> responses) {
        return sesiones.iniciar((ServerCallStreamObserver<WriteResponse>) responses, AuthInterceptor.GRANT.get(),
                AuthInterceptor.TOKEN.get());
    }
}
