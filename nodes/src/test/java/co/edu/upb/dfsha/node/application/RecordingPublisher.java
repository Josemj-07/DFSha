package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.port.EventPublisher;

import java.util.ArrayList;
import java.util.List;

/** Doble de EventPublisher que guarda lo publicado, en orden. */
final class RecordingPublisher implements EventPublisher {

    record Publicado(String routingKey, Object evento, boolean durable) {
    }

    final List<Publicado> publicados = new ArrayList<>();

    @Override
    public void publicarDurable(String routingKey, Object event) {
        publicados.add(new Publicado(routingKey, event, true));
    }

    @Override
    public void publicarVolatil(String routingKey, Object event) {
        publicados.add(new Publicado(routingKey, event, false));
    }
}
