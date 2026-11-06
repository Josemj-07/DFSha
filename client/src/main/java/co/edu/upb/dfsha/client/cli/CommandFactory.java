package co.edu.upb.dfsha.client.cli;

import java.lang.reflect.Constructor;
import java.util.Map;
import picocli.CommandLine;

/** Crea los comandos inyectando por constructor lo que declaren, sin singletons estáticos. */
public final class CommandFactory implements CommandLine.IFactory {

    private final Map<Class<?>, Object> dependencies;

    public CommandFactory(Map<Class<?>, Object> dependencies) {
        this.dependencies = Map.copyOf(dependencies);
    }

    @Override
    public <K> K create(Class<K> type) throws Exception {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length > 0 && dependencies.keySet().containsAll(java.util.List.of(parameters))) {
                Object[] arguments = java.util.Arrays.stream(parameters).map(dependencies::get).toArray();
                return type.cast(constructor.newInstance(arguments));
            }
        }
        return CommandLine.defaultFactory().create(type);
    }
}
