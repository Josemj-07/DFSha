package co.edu.upb.dfsha.node.adapter.grpc;

import co.edu.upb.dfsha.proto.v1.BlockServiceGrpc;
import co.edu.upb.dfsha.proto.v1.Chunk;
import co.edu.upb.dfsha.proto.v1.ReadRequest;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveCallsInterceptorTest {

    @Test
    void cuentaLasLlamadasMientrasEstanAbiertas() throws Exception {
        var contador = new ActiveCallsInterceptor();
        var empezo = new CountDownLatch(1);
        var pendiente = new CompletableFuture<StreamObserver<Chunk>>();
        var servicio = new BlockServiceGrpc.BlockServiceImplBase() {
            @Override
            public void leerBloque(ReadRequest request, StreamObserver<Chunk> responses) {
                pendiente.complete(responses);
                empezo.countDown();
            }
        };
        String nombre = InProcessServerBuilder.generateName();
        Server servidor = InProcessServerBuilder.forName(nombre)
                .addService(ServerInterceptors.intercept(servicio, contador)).build().start();
        ManagedChannel canal = InProcessChannelBuilder.forName(nombre).build();
        try {
            var respuesta = new CompletableFuture<Void>();
            BlockServiceGrpc.newStub(canal).leerBloque(ReadRequest.getDefaultInstance(), new StreamObserver<>() {
                @Override
                public void onNext(Chunk value) {
                }

                @Override
                public void onError(Throwable t) {
                    respuesta.completeExceptionally(t);
                }

                @Override
                public void onCompleted() {
                    respuesta.complete(null);
                }
            });
            assertTrue(empezo.await(5, TimeUnit.SECONDS));
            esperar(() -> contador.getAsInt() == 1);

            pendiente.get().onCompleted();
            respuesta.get(5, TimeUnit.SECONDS);

            esperar(() -> contador.getAsInt() == 0);
        } finally {
            canal.shutdownNow();
            servidor.shutdownNow();
        }
    }

    private static void esperar(BooleanSupplier condicion) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condicion.getAsBoolean() && System.nanoTime() < limite) {
            Thread.sleep(20);
        }
        assertTrue(condicion.getAsBoolean());
    }
}
