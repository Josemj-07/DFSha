package co.edu.upb.dfsha.node.config;

import co.edu.upb.dfsha.node.adapter.storage.BlockStoreRecovery;
import co.edu.upb.dfsha.node.application.BlockReportService;
import co.edu.upb.dfsha.node.application.HeartbeatService;
import co.edu.upb.dfsha.node.application.ScrubberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/** Tareas periódicas del nodo. El pool del scheduler tiene varios hilos: el scrubber no frena los heartbeats. */
@Configuration
@EnableScheduling
public class NodeScheduler {

    private static final Logger log = LoggerFactory.getLogger(NodeScheduler.class);
    private static final Duration PART_ABANDONADO = Duration.ofHours(1);

    private final HeartbeatService latidos;
    private final BlockReportService reportes;
    private final BlockStoreRecovery recuperacion;
    private final ScrubberService scrubber;

    public NodeScheduler(HeartbeatService latidos, BlockReportService reportes, BlockStoreRecovery recuperacion,
                         ScrubberService scrubber) {
        this.latidos = latidos;
        this.reportes = reportes;
        this.recuperacion = recuperacion;
        this.scrubber = scrubber;
    }

    @Scheduled(fixedRateString = "${dfs.heartbeat-interval}")
    public void latir() {
        latidos.latir();
    }

    /** Cada hora: block report de respaldo y limpieza de escrituras abandonadas. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    public void reportarYLimpiar() {
        reportes.publicarTodo();
        int borrados = recuperacion.limpiarPartesAbandonadas(PART_ABANDONADO);
        if (borrados > 0) {
            log.info("Se borraron {} escrituras abandonadas", borrados);
        }
    }

    @Scheduled(fixedDelayString = "${dfs.scrub-interval}", initialDelayString = "PT10M")
    public void revisarIntegridad() {
        try {
            scrubber.revisarTodo();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
