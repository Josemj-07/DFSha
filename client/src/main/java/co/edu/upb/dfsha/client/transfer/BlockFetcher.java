package co.edu.upb.dfsha.client.transfer;

import co.edu.upb.dfsha.client.data.BlockGateway;
import co.edu.upb.dfsha.client.data.ByteRange;
import co.edu.upb.dfsha.client.data.ChunkConsumer;
import co.edu.upb.dfsha.client.data.CorruptReplicaException;
import co.edu.upb.dfsha.client.data.ReplicaReadException;
import co.edu.upb.dfsha.client.integrity.Checksums;
import co.edu.upb.dfsha.client.metadata.BlockInfo;
import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.ReplicaReport;
import co.edu.upb.dfsha.client.sdk.error.DfsException;
import co.edu.upb.dfsha.client.sdk.model.ReplicaRef;
import java.security.MessageDigest;
import java.util.function.Supplier;

/** Lee un bloque probando sus réplicas en orden y reporta al servidor las que fallan. */
public final class BlockFetcher {

    private static final String UNREACHABLE = "UNREACHABLE";
    private static final String CORRUPT = "CORRUPT";

    private final BlockGateway blocks;
    private final FileSessionGateway files;

    public BlockFetcher(BlockGateway blocks, FileSessionGateway files) {
        this.blocks = blocks;
        this.files = files;
    }

    /** Lee el bloque completo y verifica su SHA-256. Cada intento reenvía el bloque desde el inicio. */
    public void leerBloque(BlockInfo block, Supplier<String> token, ChunkConsumer consumer) {
        for (ReplicaRef replica : block.replicas()) {
            try {
                MessageDigest digest = Checksums.crearSha256();
                blocks.leer(replica, block.blockId(), block.gen(), new ByteRange(0, block.len()), token.get(),
                        (offset, data) -> {
                            digest.update(data.duplicate());
                            consumer.aceptar(offset, data);
                        });
                if (Checksums.sonIguales(Checksums.convertirAHex(digest.digest()), block.sha256())) {
                    return;
                }
                reportar(block, replica, CORRUPT);
            } catch (ReplicaReadException e) {
                reportar(block, replica, UNREACHABLE);
            } catch (CorruptReplicaException e) {
                reportar(block, replica, CORRUPT);
            }
        }
        throw new NoReplicaAvailableException("Sin réplicas disponibles para el bloque " + block.idx());
    }

    /** Lee parte de un bloque. Verifica CRC32C, pero no SHA-256: no tiene el bloque completo. */
    public void leerRango(BlockInfo block, ByteRange range, Supplier<String> token, ChunkConsumer consumer) {
        for (ReplicaRef replica : block.replicas()) {
            try {
                blocks.leer(replica, block.blockId(), block.gen(), range, token.get(), consumer);
                return;
            } catch (ReplicaReadException e) {
                reportar(block, replica, UNREACHABLE);
            } catch (CorruptReplicaException e) {
                reportar(block, replica, CORRUPT);
            }
        }
        throw new NoReplicaAvailableException("Sin réplicas disponibles para el bloque " + block.idx());
    }

    private void reportar(BlockInfo block, ReplicaRef replica, String reason) {
        try {
            files.reportarReplica(new ReplicaReport(block.blockId(), block.gen(), replica.nodeId(), reason));
        } catch (DfsException e) {
            // El reporte ayuda a reparar antes, pero no debe detener la lectura.
        }
    }
}
