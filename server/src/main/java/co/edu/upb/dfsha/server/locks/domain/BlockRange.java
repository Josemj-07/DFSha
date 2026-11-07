package co.edu.upb.dfsha.server.locks.domain;

public record BlockRange(int first, int last) {
  public BlockRange {
    if (first < 0 || last < first) throw new IllegalArgumentException("Rango invalido");
  }

  public boolean solapar(BlockRange other) {
    return first <= other.last && other.first <= last;
  }

  public boolean contener(BlockRange other) {
    return first <= other.first && last >= other.last;
  }

  public static BlockRange completo() {
    return new BlockRange(0, Integer.MAX_VALUE);
  }
}
