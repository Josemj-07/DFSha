package co.edu.upb.dfsha.server.files.application;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class CommitFingerprint {
  private CommitFingerprint() {}

  static String calcular(CommitRequest request) {
    StringBuilder canonical = new StringBuilder().append(request.size()).append(';');
    anexar(canonical, request.fileSha256());
    request.blocks().stream()
        .sorted(Comparator.comparingInt(CommittedBlock::idx))
        .forEach(
            block -> {
              canonical
                  .append(block.idx())
                  .append(';')
                  .append(block.gen())
                  .append(';')
                  .append(block.len())
                  .append(';');
              anexar(canonical, block.blockId());
              anexar(canonical, block.sha256());
            });
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void anexar(StringBuilder target, String value) {
    if (value == null) target.append("-1:");
    else target.append(value.length()).append(':').append(value);
  }
}
