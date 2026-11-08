package co.edu.upb.dfsha.node.config;

import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettySslContextServerCredentials;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Arranca y detiene los servidores gRPC del nodo: 50051 para clientes y 50052 para otros nodos.
 * Ambos exigen TLS 1.3 y no existe ningún puerto en texto plano.
 */
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    // Un block token puede listar cientos de bloques; el límite por defecto de 8 KiB no alcanza.
    private static final int MAX_METADATA_BYTES = 256 * 1024;
    private static final long ESPERA_CIERRE_SEGUNDOS = 10;

    private final Path certFile;
    private final Path keyFile;
    private final ServerInterceptor auth;
    private final List<GrpcEndpoint> endpoints;
    private final Map<String, Server> servidores = new LinkedHashMap<>();
    private volatile boolean corriendo;

    public GrpcServerLifecycle(Path certFile, Path keyFile, ServerInterceptor auth, List<GrpcEndpoint> endpoints) {
        this.certFile = certFile;
        this.keyFile = keyFile;
        this.auth = auth;
        this.endpoints = List.copyOf(endpoints);
    }

    @Override
    public synchronized void start() {
        if (corriendo) {
            return;
        }
        ServerCredentials credenciales = crearCredenciales();
        try {
            for (GrpcEndpoint endpoint : endpoints) {
                Server servidor = construir(endpoint, credenciales).start();
                servidores.put(endpoint.nombre(), servidor);
                log.info("gRPC {} escuchando con TLS 1.3 en el puerto {}", endpoint.nombre(), servidor.getPort());
            }
        } catch (IOException e) {
            detenerServidores();
            throw new UncheckedIOException("no se pudo abrir un puerto gRPC", e);
        }
        corriendo = true;
        mantenerProcesoVivo();
    }

    @Override
    public synchronized void stop() {
        detenerServidores();
        corriendo = false;
    }

    @Override
    public boolean isRunning() {
        return corriendo;
    }

    /** Puerto real de un servidor; útil en pruebas, donde se pide el puerto 0 para obtener uno libre. */
    public int obtenerPuerto(String nombre) {
        Server servidor = servidores.get(nombre);
        if (servidor == null) {
            throw new IllegalStateException("el servidor " + nombre + " no está corriendo");
        }
        return servidor.getPort();
    }

    // TlsServerCredentials no deja fijar la versión de TLS; por eso se usa el SslContext de Netty.
    private ServerCredentials crearCredenciales() {
        try {
            SslContext ssl = GrpcSslContexts.configure(SslContextBuilder.forServer(certFile.toFile(), keyFile.toFile()))
                    .protocols("TLSv1.3")
                    .build();
            return NettySslContextServerCredentials.create(ssl);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("no se pudo cargar el certificado TLS del nodo", e);
        }
    }

    private Server construir(GrpcEndpoint endpoint, ServerCredentials credenciales) {
        ServerBuilder<?> builder = Grpc.newServerBuilderForPort(endpoint.port(), credenciales)
                .maxInboundMetadataSize(MAX_METADATA_BYTES);
        for (BindableService service : endpoint.services()) {
            builder.addService(ServerInterceptors.intercept(service, auth));
        }
        return builder.build();
    }

    /**
     * Una aplicación Spring sin servidor web termina cuando main() acaba. Este hilo no daemon
     * la mantiene viva mientras los servidores gRPC sigan abiertos.
     */
    private void mantenerProcesoVivo() {
        List<Server> abiertos = new ArrayList<>(servidores.values());
        Thread guardian = new Thread(() -> esperarTerminacion(abiertos), "grpc-await-termination");
        guardian.setDaemon(false);
        guardian.start();
    }

    private static void esperarTerminacion(List<Server> abiertos) {
        try {
            for (Server servidor : abiertos) {
                servidor.awaitTermination();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void detenerServidores() {
        servidores.values().forEach(Server::shutdown);
        for (Server servidor : servidores.values()) {
            try {
                if (!servidor.awaitTermination(ESPERA_CIERRE_SEGUNDOS, TimeUnit.SECONDS)) {
                    servidor.shutdownNow();
                }
            } catch (InterruptedException e) {
                servidor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        servidores.clear();
    }
}
