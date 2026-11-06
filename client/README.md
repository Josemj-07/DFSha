# Cliente DFSha

Documento de construcción del SDK y la CLI `dfs`, para personas y agentes de IA. Lee primero el [README raíz](../README.md): ahí están los contratos, los cambios frente al PDF y las [reglas de construcción](../README.md#13-reglas-de-construcción). El `build.gradle`, el código TLS y el Dockerfile de este documento fueron validados: úsalos tal cual.

## 1. Qué te corresponde

- **SDK:** partir y reensamblar bloques, E/S paralela, checksums, reintentos, failover entre réplicas y renovación de leases.
- **CLI `dfs`:** comandos de RF1, RF2 y RF3.
- **Modo `dfs shell`:** sesión interactiva con `cd`, descriptores y locks.
- **Seguridad:** validar al servidor y a los nodos con `ca.crt`, y proteger la sesión local.

El cliente solo conoce la URL del servidor y `ca.crt`. Las direcciones de los nodos siempre vienen del servidor.

**Fuera de alcance:** cifrado del lado del cliente, FUSE, `mmap`, `truncate`, compartir archivos entre usuarios, interfaz gráfica, fusión automática de conflictos.

### Orden de construcción

1. Proyecto Gradle y `Main` con Picocli. Comprueba `./gradlew installDist`.
2. `config`: `ClientConfig`, `PathResolver`.
3. `security`: `TlsFactory`.
4. `metadata`: `RestMetadataGateway`, DTO y mapeo de errores.
5. `session`: `SessionStore`, `TokenManager`.
6. Comandos `login`, `logout`, `ls`, `stat`, `cd`, `pwd`, `mkdir`, `rmdir`, `rm`, `mv`, `cp`.
7. `integrity` y `data`: `GrpcBlockGateway`, `ChannelPool`.
8. `transfer`: `Uploader`, `Downloader`, `LeaseKeeper`.
9. Comandos `put`, `get`, `cat`, `head`, `tail`.
10. `DfsFile`, `BlockBuffer`.
11. `dfs shell`, descriptores, `lock`, `unlock`.

## 2. Crear el proyecto

El cliente **no usa Spring**: una CLI debe arrancar rápido. Initializr se usa solo para obtener el wrapper de Gradle.

```bash
curl -sS https://start.spring.io/starter.tgz \
  -d type=gradle-project -d language=java -d bootVersion=4.1.2 -d javaVersion=21 \
  -d baseDir=client | tar -xzf -
cd client && rm -rf src HELP.md
mkdir -p src/main/java/co/edu/upb/dfsha/client src/test/java/co/edu/upb/dfsha/client
```

Reemplaza `settings.gradle`:

```groovy
rootProject.name = 'client'
```

Reemplaza `build.gradle`:

```groovy
plugins {
	id 'application'
	id 'com.google.protobuf' version '0.10.1'
}

group = 'co.edu.upb.dfsha'
version = '0.0.1-SNAPSHOT'

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

ext {
	grpcVersion = '1.85.1'
	protobufVersion = '4.36.2'
}

sourceSets {
	main {
		proto {
			srcDir '../proto'
		}
	}
}

protobuf {
	protoc { artifact = "com.google.protobuf:protoc:${protobufVersion}" }
	plugins { grpc { artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}" } }
	generateProtoTasks { all()*.plugins { grpc {} } }
}

dependencies {
	implementation platform("io.grpc:grpc-bom:${grpcVersion}")
	implementation 'io.grpc:grpc-netty-shaded'
	implementation 'io.grpc:grpc-protobuf'
	implementation 'io.grpc:grpc-stub'
	implementation "com.google.protobuf:protobuf-java:${protobufVersion}"
	compileOnly 'org.apache.tomcat:annotations-api:6.0.53'

	implementation 'info.picocli:picocli:4.7.7'
	annotationProcessor 'info.picocli:picocli-codegen:4.7.7'
	implementation 'org.jline:jline:3.30.6'
	implementation 'com.fasterxml.jackson.core:jackson-databind:2.20.1'

	testImplementation 'org.junit.jupiter:junit-jupiter:5.13.4'
	testImplementation 'io.grpc:grpc-inprocess'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

application {
	mainClass = 'co.edu.upb.dfsha.client.Main'
	applicationName = 'dfs'
}

tasks.withType(JavaCompile).configureEach {
	options.compilerArgs += ["-Aproject=${project.group}/${project.name}"]
}

tasks.named('test') {
	useJUnitPlatform()
}
```

`./gradlew installDist` genera el ejecutable en `build/install/dfs/bin/dfs`.

## 3. Estructura

La CLI solo interpreta argumentos e imprime. Toda la lógica vive en el SDK, que se puede usar sin CLI. Más adelante, `dfs-bench` (sección 10 del PDF) reutiliza el mismo SDK.

```
client/
├── Dockerfile
├── build.gradle
└── src/main/java/co/edu/upb/dfsha/client/
    ├── Main.java
    ├── cli/
    │   ├── DfsCommand.java       Comando raíz de Picocli
    │   ├── ShellCommand.java     Bucle interactivo con JLine
    │   ├── commands/             Una clase por comando
    │   ├── ExitCodes.java
    │   └── OutputFormatter.java
    ├── sdk/                      API pública
    │   ├── DfsClient.java        Fachada
    │   ├── DfsFile.java          open, read, write, flush, close
    │   └── model/                FileInfo, OpenMode, LockMode, LockHandle
    ├── metadata/                 MetadataGateway y RestMetadataGateway, DTO
    ├── data/                     BlockGateway y GrpcBlockGateway, ChannelPool
    ├── transfer/                 Uploader, Downloader, BlockBuffer, RetryPolicy
    ├── integrity/                Checksums, FileHash
    ├── session/                  SessionStore, TokenManager, LeaseKeeper, LocationCache
    ├── security/                 TlsFactory
    └── config/                   ClientConfig, PathResolver
```

### Configuración

| Prioridad | Fuente |
|---|---|
| 1 | Opciones `--server` y `--ca-cert` |
| 2 | Variables `DFS_SERVER_URL` y `DFS_CA_CERT` |
| 3 | `~/.dfs/config.properties` |

### Capas

```
cli  ->  sdk  ->  transfer, session  ->  metadata, data, integrity, security
```

Las dependencias van de izquierda a derecha, nunca al revés.

| Capa | Regla |
|---|---|
| `cli` | Solo Picocli: parsea, llama a `DfsClient`, formatea y devuelve código de salida. **Sin lógica de negocio, HTTP ni gRPC** |
| `sdk` | API pública. Solo expone tipos de `sdk.model` y excepciones propias. Sin tipos de Picocli, DTO ni protobuf |
| `transfer` | Algoritmos de carga, descarga, buffers y reintentos. Depende de interfaces |
| `metadata`, `data` | Adaptadores REST y gRPC |

### Interfaces

```java
public interface MetadataGateway {
    LoginResult iniciarSesion(String username, char[] password);
    LoginResult renovarSesion(String refreshToken);
    void cerrarSesion(String refreshToken);
    DirectoryPage listar(String path, String cursor);
    FileInfo obtenerAtributos(String path);
    void crearDirectorio(String path, boolean parents);
    void borrarDirectorio(String path, boolean recursive);
    void borrar(String path);
    void mover(String from, String to);
    void copiar(String from, String to);
    OpenResult abrir(OpenRequest request);
    Allocation asignar(String handle, int firstIdx, int count);
    Allocation recuperarBloque(String handle, int idx, List<String> failedNodes);
    long confirmar(String handle, CommitRequest request);
    KeepaliveResult mantenerActivo(String handle);
    void cerrar(String handle);
    LockHandle bloquear(LockRequest request);
    void renovarBloqueo(String lockId);
    void desbloquear(String lockId);
    void reportarReplica(ReplicaReport report);
}

public interface BlockGateway {
    CompletableFuture<List<Receipt>> escribir(BlockTarget target, BlockSource source, String token);
    void leer(ReplicaRef replica, BlockRange range, String token, ChunkConsumer consumer);
}

public interface DfsFile extends AutoCloseable {
    int leer(long offset, ByteBuffer destination);
    void escribir(long offset, ByteBuffer source);
    long volcar();                // Devuelve la versión confirmada.
    LockHandle bloquear(LockMode mode, BlockRange range, Duration ttl, Duration wait);
    long obtenerTamanio();
    @Override void close();
}
```

Divide `MetadataGateway` si crece, por ejemplo en `NamespaceGateway`, `FileSessionGateway` y `LockGateway`: `transfer` no debe ver métodos de autenticación.

## 4. Comandos

| Comando | Qué hace | API |
|---|---|---|
| `dfs login [usuario]`, `dfs logout` | Autentica. La contraseña se pide por consola, **nunca** como argumento | `/auth/*` |
| `dfs ls [-l] [ruta]`, `dfs stat <ruta>` | Lista y describe | `/fs/ls`, `/fs/stat` |
| `dfs cd <ruta>`, `dfs pwd` | Directorio actual, local y validado con `stat` | `/fs/stat` |
| `dfs mkdir [-p] <ruta>`, `dfs rmdir [-r] <ruta>` | Crea o borra directorios | `/fs/mkdir`, `/fs/rmdir` |
| `dfs rm`, `dfs mv`, `dfs cp` | Borra, renombra, copia COW | `/fs/*` |
| `dfs put <local> <remoto>` | Sube un archivo | REST + `EscribirBloque` |
| `dfs get <remoto> <local>` | Descarga un archivo | REST + `LeerBloque` |
| `dfs cat`, `dfs head`, `dfs tail` | Utilidades sobre `leer()` | REST + `LeerBloque` |
| `dfs shell` | Abre la sesión interactiva | |

Solo dentro de `dfs shell`:

| Comando | Qué hace |
|---|---|
| `open <ruta> <r\|w\|a\|rw>` | Devuelve un número de descriptor |
| `read <fd> <offset> <len>`, `write <fd> <offset> <datos>` | E/S por bytes |
| `flush <fd>`, `close <fd>` | Publica los cambios |
| `lock <ruta\|fd> --mode shared\|exclusive [--range a-b] [--ttl ms] [--wait ms]`, `unlock <id>` | Locks |

¿Por qué existe el shell? Un descriptor, un lock y su renovación necesitan un proceso vivo. Con `dfs open` suelto el proceso terminaría y el lease vencería a los 30 s.

Códigos de salida: `0` éxito, `1` error general, `2` uso incorrecto, `3` autenticación o permisos, `4` no encontrado, `5` conflicto, `6` servicio no disponible.

### Implementación

```java
@Command(name = "dfs", mixinStandardHelpOptions = true, version = "dfs 0.1",
         subcommands = {LoginCommand.class, LogoutCommand.class, LsCommand.class, ...})
public final class DfsCommand { ... }
```

- Una clase por comando en `cli/commands/`, implementando `Callable<Integer>`.
- Un `IFactory` propio inyecta `DfsClient` por constructor, sin singletons estáticos.
- Un `IExecutionExceptionHandler` único traduce `DfsException` a mensaje y código de salida. Mensajes en español, cortos, en `stderr`, sin stack trace salvo con `--verbose`.
- `Main` ejecuta `System.exit(new CommandLine(new DfsCommand(), factory).setExecutionExceptionHandler(handler).execute(args))`.
- `PathResolver` convierte rutas relativas con `cwd` y resuelve `.` y `..`. `cd` valida con `stat` que sea directorio y guarda `cwd`.
- `OutputFormatter`: `ls -l` con permisos, dueño, tamaño, versión, fecha y nombre.
- El progreso va a `stderr`, nunca a `stdout`, para que `dfs cat archivo > salida` funcione.
- Si un comando de RF3 se invoca fuera del shell, explica que debe usarse dentro de `dfs shell`.

### `dfs shell`

Usa el mismo árbol de comandos y agrega los exclusivos del shell en `cli/shell/`:

```java
Terminal terminal = TerminalBuilder.builder().system(true).build();
LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
while (true) {
    String line;
    try {
        line = reader.readLine(indicador());   // dfs:/home/jose>
    } catch (UserInterruptException e) {
        continue;
    } catch (EndOfFileException e) {
        break;
    }
    commandLine.execute(separar(line));
}
```

- Prompt con el `cwd`. `exit` o Ctrl-D terminan. Un error no termina el shell.
- `DescriptorTable`: mapa de enteros a `DfsFile`, y de IDs a `LockHandle`.
- Un `LeaseKeeper` renueva handles y locks de la sesión.
- Al salir: cierra descriptores y libera locks con un shutdown hook. Avisa si había datos sin `flush`.
- Para separar argumentos respeta comillas, con `reader.getParser()`.

## 5. Seguridad

`TlsFactory` crea un `SSLContext` y un `ChannelCredentials` a partir de `ca.crt`. Si el archivo no existe, falla con un mensaje claro. Confía **solo** en la CA del proyecto, no en las del sistema:

```java
KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
trustStore.load(null, null);
try (var in = Files.newInputStream(caPath)) {
    trustStore.setCertificateEntry("dfsha-ca",
            CertificateFactory.getInstance("X.509").generateCertificate(in));
}
var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
trustManagers.init(trustStore);
SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
sslContext.init(null, trustManagers.getTrustManagers(), null);

HttpClient http = HttpClient.newBuilder().sslContext(sslContext).build();
```

gRPC con la misma CA:

```java
ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
        .trustManager(caPath.toFile())
        .build();
ManagedChannel channel = Grpc.newChannelBuilderForAddress(host, port, credentials).build();
```

Block token en cada llamada:

```java
Metadata headers = new Metadata();
headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
var stub = BlockServiceGrpc.newStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
```

**No agregues `--insecure`, un `TrustManager` permisivo ni `hostnameVerifier` alguno**, ni siquiera para depurar.

`SessionStore` guarda tokens y `cwd` en `~/.dfs/session.json`, creado con permisos `600` desde el inicio:

```java
var owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
Files.createFile(path, owner);
```

Escribe en un temporal con los mismos permisos y renombra con `ATOMIC_MOVE`, para que dos procesos no dejen un JSON roto. Al leer, si los permisos son más abiertos, corrígelos y avisa.

`TokenManager` entrega el access token y lo renueva si faltan menos de 30 s. Ante un `401`, hace un solo refresh y reintenta. Si falla, lanza `NotAuthenticatedException` y la CLI dice `Sesión vencida, ejecuta dfs login`. Sincroniza el refresh para que los hilos paralelos no roten el token dos veces.

La contraseña se pide por consola, nunca en argumentos, logs ni historial. Límpiala con `Arrays.fill` tras usarla. Sin consola, léela de stdin.

```java
@Option(names = {"-p", "--password"}, interactive = true, arity = "0..1", description = "Contraseña")
char[] password;
```

## 6. Implementación

### `metadata`

`RestMetadataGateway` usa `java.net.http.HttpClient` y Jackson 2. Una clase interna centraliza el encabezado `Authorization`, JSON, tiempo límite de 10 s, `traceId` y traducción de errores. `path` y `cursor` en query se codifican con `URLEncoder`.

`ProblemMapper` traduce `application/problem+json` por su campo `code`:

```java
public sealed abstract class DfsException extends RuntimeException
        permits AuthException, NotFoundException, ConflictException,
                UnavailableException, QuotaException, DataIntegrityException { ... }
```

`ConflictException` conserva el `code` (`VERSION_CONFLICT`, `STALE_FENCING_TOKEN`, `LOCK_CONFLICT`). Cada subclase define su código de salida en la CLI.

| Respuesta | Reacción |
|---|---|
| `503 SAFE_MODE`, servidor inalcanzable | `RetryPolicy`: espera 1, 2, 4 y 8 s, hasta unos 30 s, solo en operaciones idempotentes |
| `:commit` con servidor caído | Reintenta el mismo commit: es idempotente por handle |
| `mkdir`, `POST /fs/move` tras fallo ambiguo | No reintenta a ciegas |
| `409 VERSION_CONFLICT` | No reintenta: informa al usuario para que relea |
| `409 STALE_FENCING_TOKEN`, `410 HANDLE_EXPIRED` | Aborta |
| `422 BLOCK_NOT_DURABLE` | Reenvía los bloques indicados o aborta |

### `data`

`ChannelPool`: un `ManagedChannel` por `host:port`, reutilizado. Cierra todos con `shutdown()` al terminar, o el proceso no sale. El `addr` del servidor se parte por el último `:`. El certificado del nodo debe coincidir con ese host; si no, el handshake falla y no debes evitarlo.

Secuencia de escritura de un bloque:

1. `WriteHeader` con `block_id`, `gen`, `length` y `downstream` (`nodeId` y `replicationAddr` desde el segundo nodo del pipeline).
2. `Chunk` de 1 MiB con `offset` relativo al bloque y `crc32c`.
3. `WriteTrailer` con el SHA-256 en hexadecimal.

**Control de flujo:** un `onNext` en bucle llena la memoria. Envía solo cuando el stream esté listo:

```java
class BlockUpload implements ClientResponseObserver<WriteRequest, WriteResponse> {
    @Override
    public void beforeStart(ClientCallStreamObserver<WriteRequest> stream) {
        stream.setOnReadyHandler(() -> {
            while (stream.isReady() && source.tieneSiguiente()) {
                stream.onNext(source.siguiente());
            }
            if (!source.tieneSiguiente()) {
                stream.onCompleted();
            }
        });
    }
    // onNext guarda los recibos; onError y onCompleted resuelven un CompletableFuture.
}
```

Llama a `onCompleted()` una sola vez. El manejador corre en un hilo de gRPC: leer 1 MiB local es aceptable, esperar a otro hilo no.

Tras la respuesta, comprueba que haya un recibo por cada nodo del pipeline con los mismos `blockId`, `gen`, `length` y `sha256`.

`ABORTED` trae los nodos fallidos en el trailer. Sin trailer, por ejemplo con `UNAVAILABLE` del primer nodo, el fallido es el primero del pipeline.

```java
private static final Metadata.Key<String> FAILED_NODES =
        Metadata.Key.of("dfs-failed-nodes", Metadata.ASCII_STRING_MARSHALLER);

Metadata trailers = Status.trailersFromThrowable(error);
```

En lectura, comprueba `offset` y CRC32C de cada `Chunk` antes de entregarlo; uno incorrecto lanza `CorruptReplicaException`. Un `ScheduledExecutorService` cancela la llamada si pasan 5 s sin chunk. No uses un deadline total: cortaría transferencias sanas.

### `integrity`

```java
public final class Checksums {
    public static int calcularCrc32c(ByteBuffer data) { ... }  // java.util.zip.CRC32C
}

public final class FileHash {
    public void agregarBloque(int idx, String sha256Hex);    // Admite orden arbitrario.
    public String calcular();                             // SHA-256 de los hashes en orden de idx.
}
```

`FileHash` se calcula sobre los **32 bytes crudos** de cada hash, no sobre su texto hexadecimal, y debe coincidir exactamente con el del servidor. Agrega un vector de prueba y compártelo con quien construye el servidor. `crc32c` en el proto es `fixed32`: usa `(int) crc.getValue()`.

### `put`: `Uploader`

```java
long subir(Path local, String remote, boolean exclusive);
```

1. `abrir(w)` e inicia `LeaseKeeper`, que llama a `:keepalive` cada 10 s en un hilo daemon. Si responde `410`, cancela la transferencia.
2. Divide en `ceil(size / blockSize)` bloques con el `blockSize` de `abrir`. Un archivo vacío no asigna bloques y se confirma con `size = 0`.
3. `asignar` por lotes de hasta 8 bloques.
4. Sube hasta 4 bloques en paralelo (`Semaphore(4)` y virtual threads). Cada bloque abre un `FileChannel` propio, y calcula CRC32C por chunk y SHA-256 del bloque en la misma pasada.
5. Ante `ABORTED` o `UNAVAILABLE`, pide `recuperarBloque(handle, idx, failedNodes)` y reenvía desde offset 0 con `gen + 1`. Máximo 3 intentos.
6. Calcula `fileSha256` y llama a `:commit` con los recibos.
7. `:close`. Un fallo definitivo cancela las demás tareas, hace `:close` sin commit y propaga el error.

### `get`: `Downloader`

```java
void descargar(String remote, Path local);
```

1. `abrir(r)` devuelve bloques, réplicas y `readToken`. Renuévalo con `:keepalive` antes de que venza a los 10 min.
2. Descarga hasta 4 bloques en paralelo a `<destino>.dfspart`, con `FileChannel.write(buffer, idx * blockSize + offset)`. Nunca acumules un bloque en memoria.
3. Verifica CRC32C por chunk y SHA-256 por bloque.
4. Ante `UNAVAILABLE`, `NOT_FOUND`, `DATA_LOSS`, inactividad o hash incorrecto, llama a `reportarReplica` y repite el bloque desde offset 0 con la siguiente réplica. Sin réplicas: un nuevo `abrir(r)` una vez; si tampoco hay, error con código de salida `6`.
5. Compara `fileSha256`, renombra al destino con `ATOMIC_MOVE` y llama a `:close`. Si falla, borra el temporal.

`LocationCache`: 60 s por `(path, version)`, invalidada ante cualquier fallo de réplica.

### `DfsFile` y `BlockBuffer` (sección 6 del PDF)

`BlockBuffer` guarda bloques sucios en `~/.dfs/tmp/<handle>/<idx>.buf` con permisos `600`, sin retener el bloque en memoria.

| Aspecto | Comportamiento |
|---|---|
| `escribir()` | Va al buffer local. **Retornar no implica durabilidad** |
| Bloque tocado parcialmente | Primero se descarga completo, se verifica, se modifica y se sube como bloque nuevo (COW) |
| `leer()` | Usa el buffer si el bloque está sucio. Una lectura parcial verifica CRC32C pero no SHA-256, porque no tiene el bloque completo |
| `volcar()` | Asigna bloques para los índices sucios, los sube, hace commit solo de esos y actualiza `baseVersion`. Sin cambios, no hace nada |
| `close()` | `volcar()`, `:close` y borra el directorio temporal, también ante error |
| Modo `w`, `a`, `r` | `w` empieza vacío; `a` reescribe el último bloque parcial; `r` rechaza `escribir` |
| Modo `rw` | Exige un lock que cubra el rango: compruébalo antes del commit y da un error claro |
| `VERSION_CONFLICT` en `volcar` | No reintentes ni fusiones: conserva el buffer y lanza la excepción |
| No soportado | `truncate` arbitrario, `mmap`, enlaces, compartir entre usuarios |

El SHA-256 de cada bloque se calcula sobre el buffer, y `fileSha256` combina esos hashes con los de los bloques no modificados que devolvió `abrir`.

## 7. Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY proto/ proto/
COPY client/ client/
WORKDIR /src/client
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 --user-group dfs
COPY --from=build /src/client/build/install/dfs /opt/dfs
ENV PATH="/opt/dfs/bin:${PATH}"
USER dfs
WORKDIR /home/dfs
ENTRYPOINT ["dfs"]
```

Uso con Compose:

```bash
docker compose --profile cli run --rm client login admin
docker compose --profile cli run --rm client shell
```

## 8. Pruebas y lista de entrega

Tests obligatorios. Para hilos usa `CountDownLatch` en lugar de `sleep`, y un `blockSize` pequeño, por ejemplo 4 MiB.

| Área | Casos |
|---|---|
| Rutas | `.`, `..`, absolutas, raíz, barras duplicadas |
| Bloques | Tamaños 0, 1, B−1, B, B+1, 2B; `FileHash` con orden arbitrario; vector de prueba compartido |
| Subida | Con `InProcessServerBuilder`: éxito; `ABORTED` con recuperación; reintentos agotados; recibo faltante; respeto de `isReady` |
| Descarga | Failover; CRC incorrecto; SHA incorrecto; sin réplicas; temporal borrado al fallar |
| REST | Con `com.sun.net.httpserver.HttpServer`: cada `code` a su excepción; refresh ante 401; reintento en `SAFE_MODE` |
| `DfsFile` | COW parcial, cruce de bloques, lectura del buffer, `volcar` repetido, `VERSION_CONFLICT` |
| Seguridad | Certificado de otra CA rechazado; nombre incorrecto rechazado; `session.json` en `600` |
| CLI | Códigos de salida; la contraseña nunca se imprime; `stdout` limpio en `cat` |

Comprobación sobre el sistema en marcha:

- [ ] `put` y `get` de un archivo de varios bloques producen el mismo hash.
- [ ] `get` termina bien si cae un nodo a mitad de descarga.
- [ ] `put` termina bien si cae un nodo a mitad de carga y quedan 2 vivos.
- [ ] Una réplica corrupta se detecta, se evita y se reporta.
- [ ] La memoria no crece con el tamaño del archivo.
- [ ] Con otra CA en `DFS_CA_CERT`, el cliente rechaza al servidor y a los nodos.
- [ ] Dos shells: lock exclusivo, conflicto `409`, espera con `--wait`.
