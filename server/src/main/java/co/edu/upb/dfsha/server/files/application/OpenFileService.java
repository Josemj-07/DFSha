package co.edu.upb.dfsha.server.files.application;

import co.edu.upb.dfsha.server.audit.application.AuditService;
import co.edu.upb.dfsha.server.auth.application.CurrentUser;
import co.edu.upb.dfsha.server.auth.application.TokenIssuer;
import co.edu.upb.dfsha.server.cluster.application.ClusterState;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.locks.application.LockManager;
import co.edu.upb.dfsha.server.locks.domain.*;
import co.edu.upb.dfsha.server.namespace.application.*;
import co.edu.upb.dfsha.server.namespace.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OpenFileService {
  private final MetadataStore store;
  private final AccessPolicy policy;
  private final CurrentUser current;
  private final TokenIssuer tokens;
  private final ClusterState cluster;
  private final LockManager locks;
  private final Clock clock;
  private final AuditService audit;
  private final long blockSize;

  public OpenFileService(
      MetadataStore store,
      AccessPolicy policy,
      CurrentUser current,
      TokenIssuer tokens,
      ClusterState cluster,
      LockManager locks,
      Clock clock,
      AuditService audit,
      co.edu.upb.dfsha.server.config.DfsProperties props) {
    this.store = store;
    this.policy = policy;
    this.current = current;
    this.tokens = tokens;
    this.cluster = cluster;
    this.locks = locks;
    this.clock = clock;
    this.audit = audit;
    blockSize = props.blockSizeBytes();
  }

  public OpenResult abrir(OpenRequest request) {
    String path = policy.verificar(request.path()), owner = current.obtenerNombre();
    DfsException.exigir(
        request.mode() != null && Set.of("r", "w", "a", "rw").contains(request.mode()),
        400,
        "INVALID_REQUEST",
        "Modo invalido");
    return store.escribir(
        "open-handle",
        s -> {
          boolean exists = s.directories.contenerRuta(path);
          DfsException.exigir(
              !request.exclusive() || !exists, 409, "ALREADY_EXISTS", "Archivo existente");
          if (!exists) {
            DfsException.exigir(
                request.create() && !request.mode().equals("r"),
                404,
                "NOT_FOUND",
                "Archivo inexistente");
            Inode parent = NamespaceService.obtener(s, PathNormalizer.obtenerPadre(path));
            policy.verificar(parent, true);
            DfsException.exigir(parent.directory(), 409, "NOT_DIRECTORY", "Padre no es directorio");
            long id = ++s.inodeSequence;
            s.directories.poner(path, id);
            s.inodes.put(
                id,
                new Inode(
                    id,
                    owner,
                    "rw-------",
                    false,
                    0,
                    0,
                    CommitValidator.calcularHash(List.of()),
                    clock.instant(),
                    clock.instant(),
                    List.of()));
          }
          Inode inode = NamespaceService.obtener(s, path);
          policy.verificar(inode, !request.mode().equals("r"));
          DfsException.exigir(
              !inode.directory(), 409, "INVALID_OPERATION", "No se puede abrir un directorio");
          FileLock lock = null;
          if (Set.of("w", "a").contains(request.mode()))
            lock = locks.conceder(s, inode.id(), owner, "EXCLUSIVE", BlockRange.completo(), 30000);
          String id = UUID.randomUUID().toString();
          boolean truncate = request.mode().equals("w");
          var base =
              truncate ? List.<co.edu.upb.dfsha.server.blocks.domain.Block>of() : inode.blocks();
          s.handles.put(
              id,
              new Handle(
                  id,
                  owner,
                  inode.id(),
                  request.mode(),
                  inode.version(),
                  base,
                  lock == null ? null : lock.id(),
                  clock.instant().plusSeconds(30),
                  Map.of(),
                  null,
                  null));
          List<BlockInfo> blocks =
              base.stream()
                  .map(
                      b ->
                          new BlockInfo(
                              b.idx(),
                              b.blockId(),
                              b.gen(),
                              b.len(),
                              b.sha256(),
                              cluster.obtenerReplicas(b.blockId(), b.gen(), true).stream()
                                  .map(
                                      r -> {
                                        var n = cluster.obtenerNodo(r.nodeId());
                                        return new ReplicaRef(
                                            n.nodeId(), n.obtenerDireccionCliente(), n.az());
                                      })
                                  .toList()))
                  .toList();
          String token =
              truncate
                  ? null
                  : tokens.emitirBloques(
                      owner, "r", base.stream().map(b -> b.blockId() + ":" + b.gen()).toList());
          audit.registrar(s, owner, "OPEN", "OK");
          return new OpenResult(
              id,
              inode.version(),
              blockSize,
              truncate ? 0 : inode.size(),
              truncate ? null : inode.fileSha256(),
              new Lease(lock == null ? null : lock.fencingToken(), 30000),
              token,
              blocks);
        });
  }
}
