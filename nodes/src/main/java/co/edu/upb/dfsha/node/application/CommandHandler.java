package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.NodeCommand;

/** Ejecuta un tipo de comando. Un comando nuevo es una clase nueva, no otro if. */
@FunctionalInterface
public interface CommandHandler<T extends NodeCommand> {

    void ejecutar(T comando);
}
