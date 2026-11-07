package co.edu.upb.dfsha.server.files.application;

import java.util.List;

public record RecoverRequest(int idx, List<String> failedNodes) {}
