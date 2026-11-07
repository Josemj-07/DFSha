package co.edu.upb.dfsha.server.files.application;

import java.util.List;

public record Allocation(String writeToken, List<AllocatedBlock> blocks) {}
