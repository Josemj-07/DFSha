package co.edu.upb.dfsha.client.sdk.model;

/** Lock concedido. Un rango nulo cubre todo el archivo. */
public record LockHandle(String lockId, String fencingToken, String expiresAt, LockMode mode, BlockRange range) {

    public boolean cubreParaEscritura(int firstIdx, int lastIdx) {
        if (mode != LockMode.EXCLUSIVE) {
            return false;
        }
        return range == null || (range.contiene(firstIdx) && range.contiene(lastIdx));
    }
}
