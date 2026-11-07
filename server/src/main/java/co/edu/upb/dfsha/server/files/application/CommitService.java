package co.edu.upb.dfsha.server.files.application;

import co.edu.upb.dfsha.server.audit.application.AuditService;
import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.locks.application.LockManager;
import co.edu.upb.dfsha.server.locks.domain.BlockRange;
import co.edu.upb.dfsha.server.namespace.application.NamespaceService;
import co.edu.upb.dfsha.server.namespace.domain.Inode;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CommitService {
  private final MetadataStore store;
  private final HandleAccess access;
  private final LockManager locks;
  private final CommitValidator validator;
  private final Clock clock;
  private final AuditService audit;

  public CommitService(
      MetadataStore store,
      HandleAccess access,
      LockManager locks,
      CommitValidator validator,
      Clock clock,
      AuditService audit) {
    this.store = store;
    this.access = access;
    this.locks = locks;
    this.validator = validator;
    this.clock = clock;
    this.audit = audit;
  }

  public CommitResult confirmar(String id, CommitRequest request) {
    DfsException.exigir(
        request.blocks() != null
            && request.blocks().stream().allMatch(Objects::nonNull)
            && request.size() >= 0,
        400,
        "INVALID_REQUEST",
        "Commit invalido");
    String fingerprint = CommitFingerprint.calcular(request);
    Publication publication =
        store.escribir(
            "commit-version",
            s -> {
              Handle h = access.obtener(s, id);
              access.verificarEscritura(h);
              if (fingerprint.equals(h.lastCommit()))
                return new Publication(new CommitResult(h.lastVersion()), false);
              Inode inode = s.inodes.get(h.inodeId());
              Map<Integer, Block> current = indexar(inode.blocks()), base = indexar(h.baseBlocks());
              var indices = new HashSet<Integer>();
              if (request.blocks().isEmpty())
                locks.verificar(
                    s, request.fencingToken(), h.inodeId(), h.owner(), BlockRange.completo());
              for (var block : request.blocks()) {
                CommitValidator.exigir(block.idx() >= 0 && indices.add(block.idx()));
                locks.verificar(
                    s,
                    request.fencingToken(),
                    h.inodeId(),
                    h.owner(),
                    new BlockRange(block.idx(), block.idx()));
              }
              if (h.mode().equals("w"))
                DfsException.exigir(
                    inode.version() == h.baseVersion(),
                    409,
                    "VERSION_CONFLICT",
                    "Version modificada");
              else
                for (int idx : indices)
                  DfsException.exigir(
                      Objects.equals(current.get(idx), base.get(idx)),
                      409,
                      "VERSION_CONFLICT",
                      "Bloque modificado desde apertura");
              Map<Integer, Block> finalMap =
                  h.mode().equals("w") ? new TreeMap<>(base) : new TreeMap<>(current);
              for (var block : request.blocks())
                finalMap.put(
                    block.idx(),
                    new Block(
                        block.idx(), block.blockId(), block.gen(), block.len(), block.sha256()));
              List<Block> blocks = List.copyOf(finalMap.values());
              validator.verificarMapa(blocks);
              request.blocks().forEach(block -> validator.verificarNuevo(h, block));
              long size = blocks.stream().mapToLong(Block::len).sum();
              String sha = CommitValidator.calcularHash(blocks);
              if (h.mode().equals("rw"))
                DfsException.exigir(
                    request.fileSha256() == null,
                    400,
                    "INVALID_REQUEST",
                    "rw requiere fileSha256 nulo");
              else {
                CommitValidator.exigir(size == request.size());
                CommitValidator.exigir(
                    request.fileSha256() != null
                        && MessageDigest.isEqual(
                            sha.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            request
                                .fileSha256()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
              }
              NamespaceService.verificarCuota(s, inode.owner(), size - inode.size());
              long version = inode.version() + 1;
              inode
                  .blocks()
                  .forEach(
                      b ->
                          s.unreferenced.putIfAbsent(b.blockId() + ":" + b.gen(), clock.instant()));
              blocks.forEach(
                  b -> {
                    s.pendingBlocks.remove(b.blockId() + ":" + b.gen());
                    s.unreferenced.remove(b.blockId() + ":" + b.gen());
                  });
              s.inodes.put(
                  inode.id(),
                  new Inode(
                      inode.id(),
                      inode.owner(),
                      inode.mode(),
                      false,
                      size,
                      version,
                      sha,
                      inode.ctime(),
                      clock.instant(),
                      blocks));
              var pending = new HashMap<>(h.pending());
              indices.forEach(pending::remove);
              s.handles.put(
                  id,
                  new Handle(
                      id,
                      h.owner(),
                      h.inodeId(),
                      h.mode(),
                      version,
                      blocks,
                      h.lockId(),
                      h.expiresAt(),
                      pending,
                      fingerprint,
                      version));
              audit.registrar(s, h.owner(), "COMMIT", "OK");
              return new Publication(new CommitResult(version), true);
            });
    if (publication.created()) validator.registrarReplicas(request.blocks());
    return publication.result();
  }

  private record Publication(CommitResult result, boolean created) {}

  private Map<Integer, Block> indexar(List<Block> blocks) {
    Map<Integer, Block> result = new HashMap<>();
    blocks.forEach(b -> result.put(b.idx(), b));
    return result;
  }
}
