package co.edu.upb.dfsha.client.transfer;

/**
 * Parámetros de transferencia.
 *
 * @param parallelism bloques transferidos a la vez (P)
 * @param allocationBatch bloques pedidos por asignación
 * @param writeAttempts intentos por bloque antes de abortar
 * @param chunkSize tamaño de cada fragmento en bytes
 */
public record TransferOptions(int parallelism, int allocationBatch, int writeAttempts, int chunkSize) {

    public static TransferOptions porDefecto() {
        return new TransferOptions(4, 8, 3, 1024 * 1024);
    }
}
