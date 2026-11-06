package co.edu.upb.dfsha.client.simulator;

import java.util.Arrays;
import java.util.Collection;

/** Token de bloque simplificado. El cliente lo trata como opaco, igual que uno real. */
final class SimTokens {

    static final String PREFIX = "sim.";

    private SimTokens() {
    }

    static String crear(String ops, Collection<String> blocks) {
        return PREFIX + ops + "." + String.join(",", blocks);
    }

    static boolean permite(String token, String ops, String blockId, long gen) {
        String[] parts = token.split("\\.", 3);
        return parts.length == 3 && parts[1].equals(ops)
                && Arrays.asList(parts[2].split(",")).contains(blockId + ":" + gen);
    }
}
