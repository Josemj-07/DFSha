package co.edu.upb.dfsha.server.files.application;

import co.edu.upb.dfsha.server.blocks.application.ReceiptVerifier;
import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.cluster.application.ClusterState;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.files.domain.Handle;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CommitValidator {
  private final ClusterState cluster;
  private final ReceiptVerifier receipts;
  private final long blockSize;

  public CommitValidator(
      ClusterState cluster,
      ReceiptVerifier receipts,
      co.edu.upb.dfsha.server.config.DfsProperties props) {
    this.cluster = cluster;
    this.receipts = receipts;
    blockSize = props.blockSizeBytes();
  }

  public Block verificarNuevo(Handle h, CommittedBlock block) {
    Block pending = h.pending().get(block.idx());
    exigir(
        pending != null
            && pending.blockId().equals(block.blockId())
            && pending.gen() == block.gen());
    exigir(
        block.len() > 0
            && block.len() <= blockSize
            && block.sha256() != null
            && block.sha256().matches("[0-9a-f]{64}"));
    exigir(block.receipts() != null);
    Set<String> nodes = new HashSet<>();
    for (Receipt receipt : block.receipts()) {
      var node = cluster.obtenerNodo(receipt.node());
      exigir(
          node != null
              && nodes.add(receipt.node())
              && receipts.verificar(
                  node.publicKey(),
                  block.blockId(),
                  block.gen(),
                  block.len(),
                  block.sha256(),
                  receipt.node(),
                  receipt.sig()));
    }
    exigir(nodes.size() == 2);
    return new Block(block.idx(), block.blockId(), block.gen(), block.len(), block.sha256());
  }

  public void registrarReplicas(List<CommittedBlock> blocks) {
    for (var block : blocks)
      for (var receipt : block.receipts())
        cluster.agregar(
            new co.edu.upb.dfsha.server.cluster.domain.Replica(
                receipt.node(), block.blockId(), block.gen(), block.len(), block.sha256()));
  }

  public void verificarMapa(List<Block> blocks) {
    for (int idx = 0; idx < blocks.size(); idx++) {
      Block block = blocks.get(idx);
      exigir(block.idx() == idx && (idx == blocks.size() - 1 || block.len() == blockSize));
    }
  }

  public static String calcularHash(List<Block> blocks) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Block block : blocks) digest.update(HexFormat.of().parseHex(block.sha256()));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static void exigir(boolean condition) {
    DfsException.exigir(condition, 422, "BLOCK_NOT_DURABLE", "Mapa de bloques o recibos invalidos");
  }
}
