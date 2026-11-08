package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.domain.BlockGrant;
import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;

/** Servicio de prueba: responde con el usuario y el token que dejó el interceptor en el Context. */
public class GrantEchoService extends BlockServiceGrpc.BlockServiceImplBase {

    @Override
    public void leerBloque(ReadRequest request, StreamObserver<Chunk> responses) {
        BlockGrant grant = AuthInterceptor.GRANT.get();
        String token = AuthInterceptor.TOKEN.get();
        responses.onNext(Chunk.newBuilder()
                .setData(ByteString.copyFromUtf8(grant.subject() + "|" + token))
                .build());
        responses.onCompleted();
    }
}
