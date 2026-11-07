package co.edu.upb.dfsha.server.files.application;

import co.edu.upb.dfsha.server.auth.application.TokenIssuer;
import co.edu.upb.dfsha.server.blocks.application.ReplicaPlacementPolicy;
import co.edu.upb.dfsha.server.blocks.domain.Block;
import co.edu.upb.dfsha.server.cluster.application.ClusterState;
import co.edu.upb.dfsha.server.common.DfsException;
import co.edu.upb.dfsha.server.files.domain.Handle;
import co.edu.upb.dfsha.server.journal.application.MetadataStore;
import co.edu.upb.dfsha.server.locks.application.LockManager;
import co.edu.upb.dfsha.server.locks.domain.BlockRange;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AllocationService {
  private final MetadataStore store;
  private final HandleAccess access;
  private final ReplicaPlacementPolicy placement;
  private final ClusterState cluster;
  private final TokenIssuer tokens;
  private final LockManager locks;
  private final Clock clock;
  private final long blockSize;

  public AllocationService(
      MetadataStore store,
      HandleAccess access,
      ReplicaPlacementPolicy placement,
      ClusterState cluster,
      TokenIssuer tokens,
      LockManager locks,
      Clock clock,
      co.edu.upb.dfsha.server.config.DfsProperties props) {
    this.store = store;
    this.access = access;
    this.placement = placement;
    this.cluster = cluster;
    this.tokens = tokens;
    this.locks = locks;
    this.clock = clock;
    blockSize = props.blockSizeBytes();
  }

  public Allocation asignar(String id, int first, int count) {
    DfsException.exigir(
        first >= 0 && count > 0 && count <= 1024 && first <= Integer.MAX_VALUE - count,
        400,
        "INVALID_REQUEST",
        "Asignacion invalida");
    return preparar(id, first, count, Set.of(), false);
  }

  public Allocation recuperarBloque(String id, RecoverRequest request) {
    DfsException.exigir(
        request.idx() >= 0 && request.failedNodes() != null,
        400,
        "INVALID_REQUEST",
        "Recuperacion invalida");
    return preparar(id, request.idx(), 1, Set.copyOf(request.failedNodes()), true);
  }

  private Allocation preparar(
      String id, int first, int count, Set<String> excluded, boolean recover) {
    return store.escribir(
        "allocate-blocks",
        s -> {
          Handle h = access.obtener(s, id);
          access.verificarEscritura(h);
          BlockRange range = new BlockRange(first, first + count - 1);
          var held =
              s.locks.values().stream()
                  .filter(
                      l ->
                          l.owner().equals(h.owner())
                              && l.inodeId() == h.inodeId()
                              && l.range().contener(range)
                              && l.mode().equals("EXCLUSIVE")
                              && l.expiresAt().isAfter(clock.instant()))
                  .findFirst();
          DfsException.exigir(held.isPresent(), 409, "STALE_FENCING_TOKEN", "Lock requerido");
          locks.verificar(s, held.orElseThrow().fencingToken(), h.inodeId(), h.owner(), range);
          co.edu.upb.dfsha.server.namespace.application.NamespaceService.verificarCuota(
              s,
              h.owner(),
              Math.max(
                  0,
                  Math.addExact(Math.multiplyExact((long) first + count - 1, blockSize), 1)
                      - s.inodes.get(h.inodeId()).size()));
          Map<Integer, Block> pending = new HashMap<>(h.pending());
          List<AllocatedBlock> blocks = new ArrayList<>();
          for (int idx = first; idx < first + count; idx++) {
            Block old = pending.get(idx);
            DfsException.exigir(
                !recover || old != null, 400, "INVALID_REQUEST", "Bloque no asignado");
            String block = recover ? old.blockId() : generarId();
            long gen = recover ? old.gen() + 1 : 1;
            var nodes =
                new ArrayList<>(placement.elegir(cluster.obtenerNodos(), blockSize, excluded));
            if (idx % 2 == 1) Collections.reverse(nodes);
            pending.put(idx, new Block(idx, block, gen, 0, null));
            s.pendingBlocks.putIfAbsent(block + ":" + gen, clock.instant());
            blocks.add(
                new AllocatedBlock(
                    idx,
                    block,
                    gen,
                    nodes.stream()
                        .map(
                            n ->
                                new PipelineNode(
                                    n.nodeId(),
                                    n.obtenerDireccionCliente(),
                                    n.obtenerDireccionReplica()))
                        .toList()));
          }
          s.handles.put(
              id,
              new Handle(
                  h.id(),
                  h.owner(),
                  h.inodeId(),
                  h.mode(),
                  h.baseVersion(),
                  h.baseBlocks(),
                  h.lockId(),
                  h.expiresAt(),
                  pending,
                  h.lastCommit(),
                  h.lastVersion()));
          return new Allocation(
              tokens.emitirBloques(
                  h.owner(), "w", blocks.stream().map(b -> b.blockId() + ":" + b.gen()).toList()),
              blocks);
        });
  }

  private String generarId() {
    UUID random = UUID.randomUUID();
    long ms = clock.millis();
    long most = (ms << 16) | 0x7000 | (random.getMostSignificantBits() & 0xfff);
    return "blk_" + new UUID(most, random.getLeastSignificantBits());
  }
}
