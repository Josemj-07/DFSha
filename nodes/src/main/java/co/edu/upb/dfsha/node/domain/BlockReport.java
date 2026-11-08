package co.edu.upb.dfsha.node.domain;

import java.util.List;

/** Cuerpo de node.report.&lt;id&gt;: todos los bloques completos que tiene el nodo. */
public record BlockReport(String nodeId, List<ReportedBlock> blocks) {

    public BlockReport {
        blocks = List.copyOf(blocks);
    }
}
