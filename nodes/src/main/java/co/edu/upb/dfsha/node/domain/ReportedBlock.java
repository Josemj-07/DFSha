package co.edu.upb.dfsha.node.domain;

/** Un bloque dentro del block report. */
public record ReportedBlock(String blockId, long gen, long length, String sha256) {

    public static ReportedBlock de(BlockMeta meta) {
        return new ReportedBlock(meta.key().blockId(), meta.key().gen(), meta.length(), meta.sha256());
    }
}
