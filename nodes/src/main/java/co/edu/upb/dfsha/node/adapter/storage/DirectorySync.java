package co.edu.upb.dfsha.node.adapter.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Un rename o un borrado solo son durables cuando el directorio que los contiene llega a disco.
 * Sin esto, tras un corte de luz el .blk podría "desaparecer" aunque sus datos estuvieran escritos.
 */
final class DirectorySync {

    private DirectorySync() {
    }

    static void sincronizar(Path directorio) throws IOException {
        try (var canal = FileChannel.open(directorio, StandardOpenOption.READ)) {
            canal.force(true);
        }
    }
}
