package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.node.application.BlockRead;
import co.edu.upb.dfsha.node.application.ReadBlockService;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import co.edu.upb.dfsha.proto.v1.WriteRequest;
import co.edu.upb.dfsha.proto.v1.WriteResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/** Servicio gRPC del puerto 50051, el que usan los clientes. */
public class BlockGrpcService extends BlockServiceGrpc.BlockServiceImplBase {

    /** 1 MiB, el máximo que fija el contrato para Chunk.data. */
    public static final int TAMANIO_CHUNK = 1024 * 1024;

    private final ReadBlockService lecturas;
    private final WriteSessionFactory escrituras;

    public BlockGrpcService(ReadBlockService lecturas, WriteSessionFactory escrituras) {
        this.lecturas = lecturas;
        this.escrituras = escrituras;
    }

    @Override
    public StreamObserver<WriteRequest> escribirBloque(StreamObserver<WriteResponse> responses) {
        return escrituras.iniciar((ServerCallStreamObserver<WriteResponse>) responses, AuthInterceptor.GRANT.get(),
                AuthInterceptor.TOKEN.get());
    }

    @Override
    public void leerBloque(ReadRequest request, StreamObserver<Chunk> responses) {
        var stream = (ServerCallStreamObserver<Chunk>) responses;
        BlockRead lectura;
        try {
            var key = new BlockKey(request.getBlockId(), request.getGen());
            lectura = lecturas.abrir(AuthInterceptor.GRANT.get(), key, request.getOffset(), request.getLength());
        } catch (RuntimeException e) {
            responses.onError(GrpcErrorMapper.traducir(e));
            return;
        }
        var envio = new ChunkSender(stream, lectura, TAMANIO_CHUNK);
        stream.setOnCancelHandler(envio::cancelar);
        stream.setOnReadyHandler(envio::enviarMientrasPueda);
        // Empieza ya si el cliente está listo; si no, el handler de arriba continúa cuando lo esté.
        envio.enviarMientrasPueda();
    }
}
