package co.edu.upb.dfsha.client.cli;

import co.edu.upb.dfsha.client.sdk.model.FileInfo;
import co.edu.upb.dfsha.client.sdk.model.NodeInfo;

/** Formato de salida de los comandos. */
public final class OutputFormatter {

    private OutputFormatter() {
    }

    public static String formatearDetalle(FileInfo file) {
        String kind = file.esDirectorio() ? "d" : "-";
        return String.format("%s%-9s  %-10s %14d  v%-5d %-24s %s", kind, valor(file.mode()), valor(file.owner()),
                file.size(), file.version(), valor(file.mtime()), file.name());
    }

    public static String formatearAtributos(FileInfo file) {
        return String.join(System.lineSeparator(),
                "Ruta:        " + valor(file.path()),
                "Tipo:        " + (file.esDirectorio() ? "directorio" : "archivo"),
                "Dueño:       " + valor(file.owner()),
                "Permisos:    " + valor(file.mode()),
                "Tamaño:      " + file.size() + " bytes",
                "Versión:     " + file.version(),
                "Modificado:  " + valor(file.mtime()));
    }

    public static String formatearNodo(NodeInfo node) {
        return String.format("%-8s %-24s %-12s %-9s %d/%d bytes", node.nodeId(), node.addr(), valor(node.az()),
                node.status(), node.usedBytes(), node.capacityBytes());
    }

    private static String valor(String text) {
        return text == null ? "-" : text;
    }
}
