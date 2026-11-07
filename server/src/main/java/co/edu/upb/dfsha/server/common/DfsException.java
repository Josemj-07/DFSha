package co.edu.upb.dfsha.server.common;

public class DfsException extends RuntimeException {
  private final int status;
  private final String code;

  public DfsException(int status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  public int obtenerEstado() {
    return status;
  }

  public String obtenerCodigo() {
    return code;
  }

  public static void exigir(boolean condition, int status, String code, String detail) {
    if (!condition) throw new DfsException(status, code, detail);
  }
}
