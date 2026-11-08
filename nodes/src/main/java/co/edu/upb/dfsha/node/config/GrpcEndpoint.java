package co.edu.upb.dfsha.node.config;

import io.grpc.BindableService;

import java.util.List;
import java.util.Objects;

/** Un servidor gRPC del nodo: su nombre, su puerto y los servicios que expone. */
public record GrpcEndpoint(String nombre, int port, List<BindableService> services) {

    public GrpcEndpoint {
        Objects.requireNonNull(nombre, "nombre");
        services = List.copyOf(services);
    }
}
