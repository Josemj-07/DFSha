package co.edu.upb.dfsha.node.adapter.storage;

import co.edu.upb.dfsha.node.domain.BlockAlreadyExistsException;
import co.edu.upb.dfsha.node.domain.BlockKey;
import co.edu.upb.dfsha.node.domain.BlockMeta;
import co.edu.upb.dfsha.node.domain.BlockNotFoundException;
import co.edu.upb.dfsha.node.domain.BlockWriteInProgressException;
import co.edu.upb.dfsha.node.domain.InsufficientSpaceException;
import co.edu.upb.dfsha.node.port.BlockStore;
import co.edu.upb.dfsha.node.port.BlockWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Almacén de bloques inmutables en el sistema de archivos local. */
public class FileSystemBlockStore implements BlockStore {

    private final BlockPaths paths;
    private final MetaFileCodec codec;
    private final long reservaBytes;

    /** reservaBytes: espacio que siempre se deja libre para el outbox, los logs y el sistema. */
    public FileSystemBlockStore(BlockPaths paths, MetaFileCodec codec, long reservaBytes) {
        this.paths = paths;
        this.codec = codec;
        this.reservaBytes = reservaBytes;
        try {
            Files.createDirectories(paths.obtenerRaiz());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public BlockWriter crear(BlockKey key, long length) {
        if (length < 0) {
            throw new IllegalArgumentException("length no puede ser negativo");
        }
        exigirQueNoExista(key);
        if (obtenerBytesDisponibles() < length + reservaBytes) {
            throw new InsufficientSpaceException("no hay espacio para " + length + " bytes");
        }
        FileChannel canal = abrirParcial(key);
        // Segunda comprobación: otra escritura pudo publicar el .blk entre la primera y CREATE_NEW.
        if (Files.exists(paths.obtenerCompleto(key))) {
            descartarParcial(key, canal);
            throw new BlockAlreadyExistsException("el bloque " + key.obtenerAlcance() + " ya existe");
        }
        return new FileSystemBlockWriter(key, length, paths, codec, canal);
    }

    @Override
    public Optional<BlockMeta> buscar(BlockKey key) {
        if (!Files.exists(paths.obtenerCompleto(key))) {
            return Optional.empty();
        }
        return leerMeta(key);
    }

    @Override
    public ReadableByteChannel abrir(BlockKey key, long offset) {
        try {
            FileChannel canal = FileChannel.open(paths.obtenerCompleto(key), StandardOpenOption.READ);
            if (offset < 0 || offset > canal.size()) {
                canal.close();
                throw new IllegalArgumentException("offset fuera del bloque");
            }
            return canal.position(offset);
        } catch (NoSuchFileException e) {
            throw new BlockNotFoundException("el bloque " + key.obtenerAlcance() + " no existe");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Idempotente: borrar un bloque que no existe no es un error. */
    @Override
    public void borrar(BlockKey key) {
        try {
            Files.deleteIfExists(paths.obtenerCompleto(key));
            Files.deleteIfExists(paths.obtenerMeta(key));
            sincronizarSiExiste(paths.obtenerDirectorio(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Se conserva como .corrupt para poder analizarlo; deja de existir para lecturas y reportes. */
    @Override
    public void ponerEnCuarentena(BlockKey key) {
        try {
            Path completo = paths.obtenerCompleto(key);
            if (Files.exists(completo)) {
                Files.move(completo, paths.obtenerCorrupto(key),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.deleteIfExists(paths.obtenerMeta(key));
            sincronizarSiExiste(paths.obtenerDirectorio(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<BlockMeta> listar() {
        List<BlockMeta> bloques = new ArrayList<>();
        for (Path archivo : listarArchivos()) {
            paths.interpretarNombre(archivo, BlockPaths.COMPLETO)
                    .flatMap(this::buscar)
                    .ifPresent(bloques::add);
        }
        return bloques;
    }

    @Override
    public long obtenerBytesDisponibles() {
        try {
            return Files.getFileStore(paths.obtenerRaiz()).getUsableSpace();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long obtenerBytesCapacidad() {
        try {
            return Files.getFileStore(paths.obtenerRaiz()).getTotalSpace();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Todos los archivos del almacén, sin importar su extensión. */
    List<Path> listarArchivos() {
        try (Stream<Path> archivos = Files.walk(paths.obtenerRaiz(), 2)) {
            return archivos.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Optional<BlockMeta> leerMeta(BlockKey key) {
        try {
            String contenido = Files.readString(paths.obtenerMeta(key), StandardCharsets.UTF_8);
            return codec.leer(contenido).filter(meta -> meta.key().equals(key));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void exigirQueNoExista(BlockKey key) {
        if (Files.exists(paths.obtenerCompleto(key))) {
            throw new BlockAlreadyExistsException("el bloque " + key.obtenerAlcance() + " ya existe");
        }
    }

    // CREATE_NEW falla si el .part ya existe: así dos escrituras del mismo bloque nunca se mezclan.
    private FileChannel abrirParcial(BlockKey key) {
        try {
            Files.createDirectories(paths.obtenerDirectorio(key));
            return FileChannel.open(paths.obtenerParcial(key), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new BlockWriteInProgressException("ya hay una escritura en curso de " + key.obtenerAlcance());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void descartarParcial(BlockKey key, FileChannel canal) {
        try (canal) {
            Files.deleteIfExists(paths.obtenerParcial(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sincronizarSiExiste(Path directorio) throws IOException {
        if (Files.isDirectory(directorio)) {
            DirectorySync.sincronizar(directorio);
        }
    }
}
