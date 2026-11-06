package co.edu.upb.dfsha.client.metadata;

import java.util.List;

/** Bloque asignado para escritura. */
public record AllocatedBlock(int idx, String blockId, long gen, List<PipelineNode> pipeline) {
}
