package co.edu.upb.dfsha.node.application;

import co.edu.upb.dfsha.node.domain.BlockEvent;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.port.BlockStore;
import co.edu.upb.dfsha.node.port.EventPublisher;
import co.edu.upb.dfsha.node.port.Throttle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Revisa periódicamente que ningún bloque se haya dañado en el disco (bit rot, sectores malos).
 * Recalcula el SHA-256 de cada .blk y lo compara con su .meta. Un bloque dañado pasa a
 * cuarentena y se avisa con block.corrupt: el servidor lo vuelve a copiar desde la otra réplica.
 * Sin esto, un daño solo se descubriría cuando un cliente intentara leer el bloque.
 */
public class ScrubberService {

    private static final Logger log = LoggerFactory.getLogger(ScrubberService.class);
    private static final int TAMANIO_LECTURA = 1024 * 1024;

    private final String nodeId;
    private final BlockStore store;
    private final EventPublisher eventos;
    private final Throttle limitador;

    public ScrubberService(String nodeId, BlockStore store, EventPublisher eventos, Throttle limitador) {
        this.nodeId = nodeId;
        this.store = store;
        this.eventos = eventos;
        this.limitador = limitador;
    }

    /** Revisa todos los bloques completos; los .part no se revisan porque aún se están escribiendo. */
    public ScrubReport revisarTodo() throws InterruptedException {
        int revisados = 0;
        int corruptos = 0;
        for (BlockMeta meta : store.listar()) {
            switch (revisar(meta)) {
                case SANO -> revisados++;
                case CORRUPTO -> {
                    revisados++;
                    corruptos++;
                }
                case DESAPARECIDO -> log.debug("{} se borró durante la revisión", meta.key().obtenerAlcance());
            }
        }
        log.info("Scrubber: {} bloques revisados, {} corruptos", revisados, corruptos);
        return new ScrubReport(revisados, corruptos);
    }

    enum Resultado { SANO, CORRUPTO, DESAPARECIDO }

    Resultado revisar(BlockMeta meta) throws InterruptedException {
        String calculado;
        try (ReadableByteChannel canal = store.abrir(meta.key(), 0)) {
            calculado = calcularSha256(canal, meta.length());
        } catch (BlockNotFoundException e) {
            return Resultado.DESAPARECIDO;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (calculado != null && MessageDigest.isEqual(calculado.getBytes(StandardCharsets.US_ASCII),
                meta.sha256().getBytes(StandardCharsets.US_ASCII))) {
            return Resultado.SANO;
        }
        reportarCorrupto(meta);
        return Resultado.CORRUPTO;
    }

    /** @return el hash, o null si el archivo no tiene la longitud que dice su .meta */
    private String calcularSha256(ReadableByteChannel canal, long longitudEsperada)
            throws IOException, InterruptedException {
        MessageDigest sha256 = crearDigest();
        ByteBuffer buffer = ByteBuffer.allocate(TAMANIO_LECTURA);
        long leidos = 0;
        int n;
        while ((n = canal.read(buffer)) >= 0) {
            if (n > 0) {
                limitador.adquirir(n);
                buffer.flip();
                sha256.update(buffer);
                buffer.clear();
                leidos += n;
            }
        }
        return leidos == longitudEsperada ? HexFormat.of().formatHex(sha256.digest()) : null;
    }

    private void reportarCorrupto(BlockMeta meta) {
        BlockKey key = meta.key();
        log.warn("Bloque {} corrupto: pasa a cuarentena", key.obtenerAlcance());
        store.ponerEnCuarentena(key);
        eventos.publicarDurable("block.corrupt." + nodeId, BlockEvent.de(UUID.randomUUID().toString(), nodeId, meta));
    }

    private static MessageDigest crearDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("la JVM no soporta SHA-256", e);
        }
    }
}
