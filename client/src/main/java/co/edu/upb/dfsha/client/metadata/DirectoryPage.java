package co.edu.upb.dfsha.client.metadata;

import co.edu.upb.dfsha.client.sdk.model.FileInfo;
import java.util.List;

/** Página de un listado. {@code nextCursor} es nulo en la última. */
public record DirectoryPage(List<FileInfo> entries, String nextCursor) {
}
