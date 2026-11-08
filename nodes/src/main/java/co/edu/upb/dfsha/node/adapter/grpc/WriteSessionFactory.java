package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.node.port.BlockStore;
import co.edu.upb.dfsha.node.port.EventPublisher;
import co.edu.upb.dfsha.node.port.ReceiptSigner;
import co.edu.upb.dfsha.node.port.ReplicaClient;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * Crea las sesiones de escritura. EscribirBloque (cliente) y ReplicarBloque (otro nodo) usan
 * exactamente la misma sesión: así hay un solo código para los dos caminos.
 */
public record WriteSessionFactory(String nodeId, BlockStore store, ReceiptSigner signer, ReplicaClient replicas,
                                  EventPublisher eventos) {

    public StreamObserver<WriteRequest> iniciar(ServerCallStreamObserver<WriteResponse> responses, BlockGrant grant,
                                                String token) {
        // Sin pedir mensajes de a uno, gRPC aceptaría datos más rápido de lo que el siguiente nodo los toma.
        responses.disableAutoRequest();
        var sesion = new WriteSession(this, responses, grant, token);
        responses.setOnCancelHandler(sesion::alCancelarCliente);
        responses.request(1);
        return sesion;
    }
}
