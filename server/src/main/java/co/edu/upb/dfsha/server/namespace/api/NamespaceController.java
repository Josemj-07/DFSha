package co.edu.upb.dfsha.server.namespace.api;

import co.edu.upb.dfsha.server.namespace.application.NamespaceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/fs")
public class NamespaceController {
  private final NamespaceService service;

  public NamespaceController(NamespaceService service) {
    this.service = service;
  }

  public record DirectoryRequest(String path, boolean parents) {}

  public record TransferRequest(String from, String to) {}

  @GetMapping("/stat")
  public NamespaceService.Attributes consultar(@RequestParam String path) {
    return service.consultar(path);
  }

  @GetMapping("/ls")
  public NamespaceService.Page listar(
      @RequestParam String path, @RequestParam(required = false) String cursor) {
    return service.listar(path, cursor);
  }

  @PostMapping("/mkdir")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void crear(@RequestBody DirectoryRequest body) {
    service.crearDirectorio(body.path(), body.parents());
  }

  @DeleteMapping("/rmdir")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void eliminarDirectorio(
      @RequestParam String path, @RequestParam(defaultValue = "false") boolean recursive) {
    service.eliminar(path, true, recursive);
  }

  @DeleteMapping("/files")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void eliminar(@RequestParam String path) {
    service.eliminar(path, false, false);
  }

  @PostMapping("/move")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void mover(@RequestBody TransferRequest body) {
    service.moverOCopiar(body.from(), body.to(), false);
  }

  @PostMapping("/copy")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void copiar(@RequestBody TransferRequest body) {
    service.moverOCopiar(body.from(), body.to(), true);
  }
}
