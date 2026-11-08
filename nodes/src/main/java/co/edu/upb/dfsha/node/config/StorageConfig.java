package co.edu.upb.dfsha.node.config;

import co.edu.upb.dfsha.node.adapter.crypto.CertificateHostCheck;
import co.edu.upb.dfsha.node.adapter.crypto.EcdsaReceiptSigner;
import co.edu.upb.dfsha.node.adapter.crypto.JwtTokenVerifier;
import co.edu.upb.dfsha.node.adapter.crypto.PemKeys;
import co.edu.upb.dfsha.node.adapter.grpc.TransferRateLimiter;
import co.edu.upb.dfsha.node.adapter.storage.BlockPaths;
import co.edu.upb.dfsha.node.adapter.storage.BlockStoreRecovery;
import co.edu.upb.dfsha.node.adapter.storage.FileSystemBlockStore;
import co.edu.upb.dfsha.node.adapter.storage.MetaFileCodec;
import co.edu.upb.dfsha.node.domain.NodeIdentity;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;

/** Almacén de bloques, identidad del nodo y claves. */
@Configuration
@EnableConfigurationProperties(NodeProperties.class)
public class StorageConfig {

    // Espacio que siempre queda libre para el outbox, los logs y el sistema operativo.
    private static final long RESERVA_DISCO_BYTES = 512L * 1024 * 1024;

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    BlockPaths rutasBloques(NodeProperties props) {
        return new BlockPaths(props.dataDir());
    }

    @Bean
    FileSystemBlockStore almacen(BlockPaths rutas) {
        return new FileSystemBlockStore(rutas, new MetaFileCodec(), RESERVA_DISCO_BYTES);
    }

    /** Se ejecuta antes de abrir los puertos: ninguna escritura a medias de una caída anterior sobrevive. */
    @Bean
    BlockStoreRecovery recuperacion(FileSystemBlockStore almacen, BlockPaths rutas, Clock reloj) {
        var recuperacion = new BlockStoreRecovery(almacen, rutas, reloj);
        recuperacion.recuperar();
        return recuperacion;
    }

    @Bean
    NodeIdentity identidad(NodeProperties props) {
        var certificado = PemKeys.cargarCertificado(props.obtenerCertificado());
        CertificateHostCheck.verificar(certificado, props.advertisedHost());
        try {
            return new NodeIdentity(props.nodeId(), props.advertisedHost(), props.clientPort(),
                    props.replicationPort(), props.az(), Files.readString(props.obtenerCertificado()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Bean
    JwtTokenVerifier verificadorTokens(NodeProperties props, Clock reloj) {
        return new JwtTokenVerifier(PemKeys.cargarPublicaEd25519(props.obtenerClaveTokens()), reloj);
    }

    @Bean
    EcdsaReceiptSigner firmaRecibos(NodeProperties props) {
        return new EcdsaReceiptSigner(props.nodeId(), PemKeys.cargarPrivadaEc(props.obtenerClavePrivada()));
    }

    /** Compartido por la re-replicación y el scrubber: juntos no superan la velocidad configurada. */
    @Bean
    TransferRateLimiter limitadorTransferencias(NodeProperties props) {
        return new TransferRateLimiter(props.repairRateMbPerSec() * 1_000_000);
    }
}
