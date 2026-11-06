package co.edu.upb.dfsha.client.transfer;

import co.edu.upb.dfsha.client.data.BlockGateway;
import co.edu.upb.dfsha.client.data.BlockSource;
import co.edu.upb.dfsha.client.data.BlockTarget;
import co.edu.upb.dfsha.client.data.Receipt;
import co.edu.upb.dfsha.client.data.ReplicaWriteException;
import co.edu.upb.dfsha.client.metadata.AllocatedBlock;
import co.edu.upb.dfsha.client.metadata.Allocation;
import co.edu.upb.dfsha.client.metadata.BlockReceipt;
import co.edu.upb.dfsha.client.metadata.CommittedBlock;
import co.edu.upb.dfsha.client.metadata.FileSessionGateway;
import co.edu.upb.dfsha.client.metadata.PipelineNode;
import co.edu.upb.dfsha.client.sdk.error.UnavailableException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** Escribe un bloque en su cadena de nodos y lo recupera con un pipeline nuevo si un nodo falla. */
public final class BlockPublisher {

    private final BlockGateway blocks;
    private final FileSessionGateway files;
    private final int maxAttempts;

    public BlockPublisher(BlockGateway blocks, FileSessionGateway files, TransferOptions options) {
        this.blocks = blocks;
        this.files = files;
        this.maxAttempts = options.writeAttempts();
    }

    /** @param sources crea un origen nuevo en cada intento, porque el bloque se reenvía desde el inicio */
    CommittedBlock publicar(String handle, AllocatedBlock allocated, String writeToken, long length,
                            Supplier<BlockSource> sources) {
        AllocatedBlock current = allocated;
        String token = writeToken;
        for (int attempt = 1; ; attempt++) {
            BlockSource source = sources.get();
            try {
                List<Receipt> receipts = esperar(blocks.escribir(crearDestino(current, length), source, token));
                return crearConfirmado(current, length, source.obtenerSha256(), receipts);
            } catch (ReplicaWriteException e) {
                if (!e.esRecuperable() || attempt >= maxAttempts) {
                    throw new UnavailableException("WRITE_FAILED",
                            "No se pudo escribir el bloque " + current.idx() + " tras " + attempt + " intentos", e);
                }
                Allocation recovered = files.recuperarBloque(handle, current.idx(), e.obtenerNodosFallidos());
                current = recovered.blocks().get(0);
                token = recovered.writeToken();
            }
        }
    }

    private List<Receipt> esperar(CompletableFuture<List<Receipt>> pending) {
        try {
            return pending.get();
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnavailableException("INTERRUPTED", "Transferencia cancelada", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private BlockTarget crearDestino(AllocatedBlock block, long length) {
        PipelineNode first = block.pipeline().get(0);
        List<BlockTarget.Hop> hops = block.pipeline().stream().skip(1)
                .map(node -> new BlockTarget.Hop(node.nodeId(), node.replicationAddr()))
                .toList();
        return new BlockTarget(block.blockId(), block.gen(), length, first.nodeId(), first.clientAddr(), hops);
    }

    private CommittedBlock crearConfirmado(AllocatedBlock block, long length, String sha256, List<Receipt> receipts) {
        List<BlockReceipt> valid = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (PipelineNode node : block.pipeline()) {
            receipts.stream()
                    .filter(receipt -> coincide(receipt, node, block, length, sha256))
                    .findFirst()
                    .ifPresentOrElse(
                            receipt -> valid.add(new BlockReceipt(node.nodeId(),
                                    Base64.getEncoder().encodeToString(receipt.signature()))),
                            () -> missing.add(node.nodeId()));
        }
        if (!missing.isEmpty()) {
            throw new ReplicaWriteException(io.grpc.Status.Code.ABORTED, missing,
                    "Faltan recibos de " + missing, null);
        }
        return new CommittedBlock(block.idx(), block.blockId(), block.gen(), length, sha256, valid);
    }

    private boolean coincide(Receipt receipt, PipelineNode node, AllocatedBlock block, long length, String sha256) {
        return receipt.nodeId().equals(node.nodeId())
                && receipt.blockId().equals(block.blockId())
                && receipt.gen() == block.gen()
                && receipt.length() == length
                && sha256.equalsIgnoreCase(receipt.sha256());
    }
}
