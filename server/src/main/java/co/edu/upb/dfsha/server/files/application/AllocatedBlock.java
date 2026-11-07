package co.edu.upb.dfsha.server.files.application;

import java.util.List;

public record AllocatedBlock(int idx, String blockId, long gen, List<PipelineNode> pipeline) {}
