package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.DeleteBlockCommand;
import co.edu.upb.dfsha.node.domain.ResyncCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandDispatcherTest {

    private final List<String> ejecutados = new ArrayList<>();
    private final CommandDispatcher dispatcher = new CommandDispatcher()
            .registrar(ResyncCommand.class, comando -> ejecutados.add("resync:" + comando.commandId()));

    @Test
    void enviaCadaComandoASuHandler() {
        assertTrue(dispatcher.despachar(new ResyncCommand("c-1")));

        assertEquals(List.of("resync:c-1"), ejecutados);
    }

    @Test
    void unComandoRepetidoSeIgnora() {
        dispatcher.despachar(new ResyncCommand("c-1"));

        assertFalse(dispatcher.despachar(new ResyncCommand("c-1")));
        assertEquals(1, ejecutados.size());
    }

    @Test
    void unComandoQueFalloSePuedeReintentar() {
        int[] intentos = {0};
        var conFalla = new CommandDispatcher().registrar(ResyncCommand.class, comando -> {
            if (intentos[0]++ == 0) {
                throw new IllegalStateException("falla transitoria");
            }
        });

        assertThrows(IllegalStateException.class, () -> conFalla.despachar(new ResyncCommand("c-1")));
        assertTrue(conFalla.despachar(new ResyncCommand("c-1")));
    }

    @Test
    void unComandoSinHandlerEsUnError() {
        var borrar = new DeleteBlockCommand("c-2", new BlockKey("blk_01", 1));

        assertThrows(IllegalArgumentException.class, () -> dispatcher.despachar(borrar));
    }
}
