package co.edu.upb.dfsha.server.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ProblemAdvice {
  @ExceptionHandler(DfsException.class)
  public ResponseEntity<ProblemDetail> manejar(DfsException ex, HttpServletRequest request) {
    var p =
        ProblemDetail.forStatusAndDetail(
            HttpStatusCode.valueOf(ex.obtenerEstado()), ex.getMessage());
    p.setTitle(ex.obtenerCodigo());
    p.setType(URI.create("/errors/" + ex.obtenerCodigo().toLowerCase().replace('_', '-')));
    p.setProperty("code", ex.obtenerCodigo());
    p.setProperty("traceId", request.getAttribute("traceId"));
    return ResponseEntity.status(ex.obtenerEstado())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(p);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<ProblemDetail> entrada(Exception ex, HttpServletRequest request) {
    return manejar(new DfsException(400, "INVALID_REQUEST", "Entrada invalida"), request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> inesperado(Exception ex, HttpServletRequest request) {
    org.slf4j.LoggerFactory.getLogger(ProblemAdvice.class)
        .error("Error interno: {}", ex.getClass().getSimpleName());
    return manejar(
        new DfsException(500, "INTERNAL_ERROR", "No se pudo completar la operacion"), request);
  }
}
