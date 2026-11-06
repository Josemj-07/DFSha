package co.edu.upb.dfsha.client.metadata;

import java.util.List;

/** Respuesta de asignación o de recuperación de un bloque. */
public record Allocation(String writeToken, List<AllocatedBlock> blocks) {
}
