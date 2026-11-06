# Nodos esclavos (DataNodes)

Documento de construcción de los DataNodes, para personas y agentes de IA. Lee primero el [README raíz](../README.md): ahí están los contratos, los cambios frente al PDF y las [reglas de construcción](../README.md#13-reglas-de-construcción). El `build.gradle`, la configuración, el arranque TLS y el Dockerfile de este documento fueron validados: úsalos tal cual.

## 1. Qué te corresponde

- Guardar y servir bloques inmutables por gRPC con TLS.
- Replicar en cadena hacia el siguiente nodo y ejecutar re-replicación por comando.
- Verificar integridad: CRC32C por chunk, SHA-256 por bloque y scrubber periódico.
- Enviar heartbeats, registro, block reports y eventos por RabbitMQ, y ejecutar comandos.
- Firmar recibos de bloques con la clave TLS del nodo.
- Mantener `proto/`, `docker-compose.yml`, `.env.example`, `.dockerignore` y `.gitignore`, cuyo contenido está en el README raíz.

Un solo proyecto produce una sola imagen. `dn-1`, `dn-2` y `dn-3` se diferencian únicamente por variables de entorno y certificados.

El nodo no conoce archivos ni usuarios: solo `(blockId, gen)`.

**Fuera de alcance:** rutas de archivos, usuarios, decidir dónde va una réplica, HTTP, recibir conexiones del servidor.

### Orden de construcción

1. Piezas compartidas: `proto/dfsha/v1/block.proto`, `docker-compose.yml`, `.env.example`, `.dockerignore` y `.gitignore`, copiados del README raíz.
2. Genera el proyecto y comprueba que `./gradlew build` genera las clases gRPC.
3. `domain` y `port`.
4. `FileSystemBlockStore`, con tests de escritura atómica.
5. `PemKeys`, `JwtTokenVerifier`, `EcdsaReceiptSigner`.
6. `GrpcServerLifecycle` y `AuthInterceptor`. Comprueba que TLS 1.2 y texto plano fallan.
7. `LeerBloque`.
8. `EscribirBloque` en un nodo y después la cadena con `ReplicarBloque`.
9. `AmqpEventPublisher`, `HeartbeatService`, `BlockReportService`.
10. `CommandListener`, borrado y re-replicación.
11. `OutboxStore`.
12. `ScrubberService`.

## 2. Crear el proyecto

Desde la raíz del repositorio. No incluye Spring Web porque el nodo no expone HTTP.

```bash
curl -sS https://start.spring.io/starter.tgz \
  -d type=gradle-project -d language=java -d bootVersion=4.1.2 -d javaVersion=21 \
  -d groupId=co.edu.upb.dfsha -d artifactId=node -d name=node \
  -d packageName=co.edu.upb.dfsha.node -d baseDir=nodes \
  -d dependencies=rabbitmq,validation | tar -xzf -
```

En `build.gradle` agrega el plugin, la configuración de protobuf y las dependencias:

```groovy
plugins {
	// Deja los tres plugins que genera Initializr y agrega:
	id 'com.google.protobuf' version '0.10.1'
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
	// Agrega a las que genera Initializr:
	implementation 'org.springframework.boot:spring-boot-starter-jackson'
	implementation platform("io.grpc:grpc-bom:${grpcVersion}")
	implementation 'io.grpc:grpc-netty-shaded'
	implementation 'io.grpc:grpc-protobuf'
	implementation 'io.grpc:grpc-stub'
	implementation "com.google.protobuf:protobuf-java:${protobufVersion}"
	compileOnly 'org.apache.tomcat:annotations-api:6.0.53'
	implementation 'io.jsonwebtoken:jjwt-api:0.13.0'
	runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.13.0'
	runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.13.0'
}
```

> **Cuidado:** sin `spring-boot-starter-jackson` el nodo falla al arrancar con `NoClassDefFoundError: tools/jackson/core/JacksonException`. En Spring Boot 4 el starter de AMQP no trae Jackson.

Usamos `grpc-java` directamente y no `spring-grpc` porque el nodo necesita dos servidores gRPC, en los puertos 50051 y 50052.

## 3. Estructura

Arquitectura hexagonal. El dominio y los casos de uso no conocen gRPC, RabbitMQ ni el sistema de archivos: solo hablan con interfaces (puertos).

```
nodes/
├── Dockerfile
├── build.gradle
└── src/main/java/co/edu/upb/dfsha/node/
    ├── NodeApplication.java
    ├── config/              NodeProperties, GrpcServerLifecycle, AmqpConfig
    ├── domain/              BlockKey, BlockMeta, Receipt, Command, excepciones
    ├── port/                BlockStore, EventPublisher, TokenVerifier, ReceiptSigner, ReplicaClient
    ├── application/         WriteBlockService, ReadBlockService, ReplicateBlockService,
    │                        DeleteBlockService, BlockReportService, HeartbeatService, ScrubberService
    └── adapter/
        ├── grpc/            BlockGrpcService, ReplicationGrpcService, AuthInterceptor,
        │                    GrpcReplicaClient, GrpcErrorMapper
        ├── storage/         FileSystemBlockStore, BlockPaths
        ├── amqp/            AmqpEventPublisher, CommandListener, OutboxStore
        └── crypto/          JwtTokenVerifier, EcdsaReceiptSigner, PemKeys
```

### Configuración

| Variable | Ejemplo | Uso |
|---|---|---|
| `DFS_NODE_ID` | `dn-1` | Identidad; también es el usuario AMQP |
| `DFS_NODE_AZ` | `us-east-1a` | Zona que usa la política de réplicas |
| `DFS_ADVERTISED_HOST` | `dn-1` o `10.0.1.21` | Dirección que se publica. **Debe estar en el SAN del certificado** |
| `DFS_RABBIT_HOST` | `rabbitmq` o `10.0.1.10` | Dirección del broker. **Debe estar en el SAN del certificado de RabbitMQ** |
| `DFS_RABBIT_PASS` | | Contraseña AMQP |
| `DFS_TLS_DIR` | `/tls` | `tls.crt`, `tls.key`, `ca.crt`, `blocktoken.pub` |
| `DFS_DATA_DIR` | `/data` | Bloques y outbox |

`src/main/resources/application.properties`:

```properties
spring.application.name=node

spring.ssl.bundle.pem.broker.truststore.certificate=file:${DFS_TLS_DIR:/tls}/ca.crt
spring.ssl.bundle.pem.broker.protocol=TLSv1.3
spring.rabbitmq.host=${DFS_RABBIT_HOST}
spring.rabbitmq.port=5671
spring.rabbitmq.virtual-host=dfsha
spring.rabbitmq.username=${DFS_NODE_ID}
spring.rabbitmq.password=${DFS_RABBIT_PASS}
spring.rabbitmq.ssl.enabled=true
spring.rabbitmq.ssl.bundle=broker
spring.rabbitmq.publisher-confirm-type=correlated

dfs.node-id=${DFS_NODE_ID}
dfs.az=${DFS_NODE_AZ}
dfs.advertised-host=${DFS_ADVERTISED_HOST}
dfs.client-port=50051
dfs.replication-port=50052
dfs.data-dir=${DFS_DATA_DIR:/data}
dfs.tls-dir=${DFS_TLS_DIR:/tls}
dfs.heartbeat-interval=3s
dfs.scrub-interval=24h
dfs.repair-rate-mb-per-sec=50
```

### Puertos

```java
public interface BlockStore {
    BlockWriter crear(BlockKey key, long length);      // ALREADY_EXISTS si ya está completo
    Optional<BlockMeta> buscar(BlockKey key);
    ReadableByteChannel abrir(BlockKey key, long offset);
    void borrar(BlockKey key);
    void ponerEnCuarentena(BlockKey key);
    List<BlockMeta> listar();
    long obtenerBytesDisponibles();
    long obtenerBytesCapacidad();
}

public interface BlockWriter extends AutoCloseable {
    void escribir(ByteBuffer data);
    BlockMeta confirmar(String expectedSha256);            // Publica .blk. Lanza si no coincide.
    void abortar();                                     // Borra .part.
}

public interface TokenVerifier { BlockGrant verificar(String token); }
public interface ReceiptSigner { Receipt firmar(BlockMeta meta); }
public interface EventPublisher {
    void publicarDurable(String routingKey, Object event);  // Pasa por el outbox.
    void publicarVolatil(String routingKey, Object event); // Heartbeats: se puede perder.
}
public interface ReplicaClient { ReplicaStream abrir(Downstream target, String token, WriteHeader header); }
```

`record BlockKey(String blockId, long gen)` valida `blockId` con una expresión estricta como `^blk_[0-9A-Za-z-]+$`. **`blockId` viene de la red y forma parte de una ruta de archivo: sin esta validación hay path traversal.**

`record BlockGrant(String subject, Set<Op> ops, Set<String> blocks)` con `permite(Op, BlockKey)`.

## 4. Seguridad

Certificado: en Compose lo crea el servicio `pki`. En producción sigue la [emisión de certificados](../server/README.md#emisión-en-producción). El nodo genera su clave y solo sale de la máquina el CSR. El usuario `10001` del contenedor debe poder leer `tls.key`.

`GrpcServerLifecycle implements SmartLifecycle` crea dos `Server`, uno por puerto, con TLS 1.3 obligatorio:

```java
var sslContext = GrpcSslContexts
        .configure(SslContextBuilder.forServer(certFile, keyFile))
        .protocols("TLSv1.3")
        .build();
ServerCredentials credentials = NettySslContextServerCredentials.create(sslContext);

Server server = Grpc.newServerBuilderForPort(port, credentials)
        .maxInboundMetadataSize(256 * 1024)
        .addService(ServerInterceptors.intercept(service, authInterceptor))
        .build()
        .start();
```

Estas clases se importan de `io.grpc.netty.shaded.io.grpc.netty` y `io.grpc.netty.shaded.io.netty.handler.ssl`. No uses `TlsServerCredentials`: no permite fijar la versión de TLS.

`maxInboundMetadataSize` es necesario: el límite por defecto es 8 KiB y un token que lista cientos de bloques no cabe.

**El proceso debe seguir vivo:** una aplicación Spring sin web puede terminar si nada la retiene. Mantén un hilo no daemon en `awaitTermination()`. `stop()` llama a `shutdown()` y luego `awaitTermination(10, SECONDS)`.

Canal hacia otro nodo, validando con la CA:

```java
ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
        .trustManager(caFile)
        .build();
ManagedChannel channel = Grpc.newChannelBuilderForAddress(host, port, credentials).build();
```

`AuthInterceptor`, en ambos puertos:

```java
private static final Metadata.Key<String> AUTHORIZATION =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
static final Context.Key<BlockGrant> GRANT = Context.key("dfs-grant");

@Override
public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
    try {
        BlockGrant grant = tokens.verificar(extraerToken(headers));
        return Contexts.interceptCall(Context.current().withValue(GRANT, grant), call, headers, next);
    } catch (InvalidTokenException e) {
        call.close(Status.UNAUTHENTICATED.withDescription("token inválido"), new Metadata());
        return new ServerCall.Listener<>() { };
    }
}
```

`JwtTokenVerifier`: `Jwts.parser().verifyWith(publicKey).requireAudience("dfsha-datanode").build().parseSignedClaims(token)`. Lee la clave pública con `X509EncodedKeySpec` y `KeyFactory.getInstance("Ed25519")`, y la privada del nodo con `PKCS8EncodedKeySpec` y `KeyFactory.getInstance("EC")`.

| Control | Implementación |
|---|---|
| Alcance | Cada servicio comprueba `ops` y que `"<blockId>:<gen>"` esté en `blk` |
| Cadena | El nodo reenvía el mismo token al siguiente nodo: guarda el valor crudo en otra `Context.Key` |
| Re-replicación | Usa el token que llega en el comando `REPLICATE_BLOCK` |
| AMQP | Todos los mensajes llevan `user_id` igual a `DFS_NODE_ID` |

Recibo, idéntico byte a byte al que verifica el servidor:

```java
String payload = "dfsha-receipt-v1|" + blockId + "|" + gen + "|" + length + "|" + sha256 + "|" + nodeId;
Signature signer = Signature.getInstance("SHA256withECDSA");
signer.initSign(tlsPrivateKey);
signer.update(payload.getBytes(StandardCharsets.UTF_8));
byte[] signature = signer.sign();
```

## 5. Almacenamiento

Todas las rutas las genera solo `BlockPaths`:

```
/data/blocks/<últimos 2 caracteres del id>/
    blk_<id>_<gen>.part    En escritura
    blk_<id>_<gen>.blk     Completo e inmutable
    blk_<id>_<gen>.meta    JSON: blockId, gen, length, sha256
    blk_<id>_<gen>.corrupt En cuarentena
/data/outbox/events.jsonl  Eventos pendientes de confirmar
```

`BlockWriter.confirmar`, orden obligatorio:

1. La longitud escrita es igual a la del header. Si no: `DATA_LOSS`.
2. El SHA-256 coincide, comparado con `MessageDigest.isEqual`. Si no: `DATA_LOSS`.
3. Escribe `.meta` y aplica `FileChannel.force(true)` a `.part` y `.meta`.
4. `Files.move(part, blk, StandardCopyOption.ATOMIC_MOVE)`.
5. `fsync` del directorio: `try (var d = FileChannel.open(dir, READ)) { d.force(true); }`.

El nombre `.blk` es la marca de bloque completo: aparece **al final** y `.meta` debe existir antes. Solo después de este método se firma un recibo.

- Un bloque nunca se reescribe. Si `(blockId, gen)` ya existe, responde `ALREADY_EXISTS`.
- Dos escrituras concurrentes de la misma clave: abre `.part` con `CREATE_NEW` y responde `ABORTED` a la segunda.
- Si `obtenerBytesDisponibles() < length + reserva`, responde `RESOURCE_EXHAUSTED` antes de aceptar datos.
- Al arrancar: borra todos los `.part`, carga el `.meta` de cada `.blk` y pone en cuarentena los inconsistentes.
- Cada hora limpia los `.part` con más de 1 h sin actividad.

## 6. Implementación

### Escritura y cadena

`BlockGrpcService.escribirBloque` y `ReplicationGrpcService.replicarBloque` comparten un `WriteSession`: un solo código para ambos caminos.

```java
@Override
public StreamObserver<WriteRequest> escribirBloque(StreamObserver<WriteResponse> responses) {
    var serverStream = (ServerCallStreamObserver<WriteResponse>) responses;
    serverStream.disableAutoRequest();
    var session = sessionFactory.crear(serverStream, AuthInterceptor.GRANT.get());
    serverStream.request(1);
    return session;
}
```

Estados de `WriteSession`: `AWAIT_HEADER`, `RECEIVING`, `AWAIT_DOWNSTREAM`, `DONE`. Un mensaje fuera de orden da `INVALID_ARGUMENT`.

| Mensaje | Acción |
|---|---|
| `header` | Comprueba `grant.permite(WRITE, key)`, crea `BlockWriter`, abre el stream hacia `downstream[0]` si existe y le reenvía el header con el resto de la lista |
| `chunk` | `offset` igual al esperado; CRC32C (`java.util.zip.CRC32C`); escribe; actualiza SHA-256; reenvía. Sin acumular en memoria |
| `trailer` | Reenvía; `writer.confirmar(sha256)`; firma el recibo; espera la respuesta del siguiente nodo si existe |
| Fin | Responde con el recibo propio más los del siguiente nodo y publica `block.received` |

**Control de flujo:** no aceptes datos más rápido de lo que acepta el siguiente nodo, o se llena la memoria. Tras cada mensaje llama a `serverStream.request(1)` solo si el siguiente nodo tiene `isReady()`. Si no, deja pendiente esa solicitud y la cumple desde `setOnReadyHandler` del stream de salida. Sin siguiente nodo, pide siempre.

Errores, siempre con `writer.abortar()` y cancelando el stream de salida:

| Situación | Respuesta |
|---|---|
| CRC o SHA incorrecto | `DATA_LOSS` con el offset en la descripción |
| Falla el siguiente nodo | `ABORTED` con trailer `dfs-failed-nodes` |
| El siguiente nodo responde `ABORTED` | Propaga el mismo trailer |
| El cliente cancela | Observa `setOnCancelHandler` y aborta |
| Disco | `RESOURCE_EXHAUSTED` |

```java
Metadata trailers = new Metadata();
trailers.put(FAILED_NODES, String.join(",", failedNodes));
responses.onError(Status.ABORTED.withDescription("falló la cadena").asRuntimeException(trailers));
```

`GrpcErrorMapper` centraliza la traducción de excepciones de dominio a `Status`.

`GrpcReplicaClient` cachea un `ManagedChannel` por dirección. Si un stream no avanza en 5 s, cuenta como nodo fallido.

### Lectura

`leerBloque` comprueba permiso `READ`, valida `gen` y rango, y envía chunks de 1 MiB con CRC32C. Usa `ServerCallStreamObserver` y `setOnReadyHandler`, enviando solo mientras `isReady()`, y cierra el canal del archivo en `setOnCancelHandler`. Un bloque en cuarentena responde `NOT_FOUND`.

### AMQP

Sin beans `Queue`, `Exchange` ni `Binding`: el usuario del nodo no tiene permiso `configure`.

```java
@Bean
MessageConverter conversorMensajes() {
    return new JacksonJsonMessageConverter();
}
```

`AmqpEventPublisher`: **todo** mensaje lleva `user_id`, o RabbitMQ no podrá comprobar la identidad:

```java
rabbitTemplate.convertAndSend("dfs.events", routingKey, event, message -> {
    message.getMessageProperties().setUserId(nodeId);
    return message;
});
```

- `publicarVolatil`: `MessageDeliveryMode.NON_PERSISTENT`; captura `AmqpException` y descarta.
- `publicarDurable`: guarda en `OutboxStore` y publica con `CorrelationData`. Borra del outbox cuando `correlation.getFuture().get(5, SECONDS).ack()` sea verdadero.

| Clase | Función |
|---|---|
| `HeartbeatService` | `@Scheduled(fixedRateString = "${dfs.heartbeat-interval}")`, por `publicarVolatil`. `activeConnections` es un contador de streams abiertos |
| `BlockReportService` | Publica `node.register` y `node.report` al arrancar, al reconectar (`ConnectionListener`), al recibir `RESYNC` y cada hora |
| `OutboxStore` | Archivo JSONL con `fsync`, drenado en orden por un hilo. Los eventos llevan `eventId` porque se pueden entregar más de una vez |

`node.register.<id>` lleva `certPem`, el contenido de `tls.crt`: con él el servidor valida la identidad del nodo y obtiene la clave para verificar recibos. Si `DFS_ADVERTISED_HOST` no está en el SAN, los clientes fallarán el handshake: compruébalo al arrancar y detén el proceso con un mensaje claro.

**Heartbeat de aplicación y heartbeat AMQP son cosas distintas:** el protocolo tiene su propio keepalive de conexión. El detector de fallas del servidor usa únicamente el mensaje `node.heartbeat.<id>`.

### Comandos

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReplicateBlockCommand.class, name = "REPLICATE_BLOCK"),
        @JsonSubTypes.Type(value = DeleteBlockCommand.class, name = "DELETE_BLOCK"),
        @JsonSubTypes.Type(value = ResyncCommand.class, name = "RESYNC")})
public sealed interface Command permits ReplicateBlockCommand, DeleteBlockCommand, ResyncCommand {
    String commandId();
}
```

`CommandListener` usa `@RabbitListener(queues = "dn.${dfs.node-id}.commands")` y delega en un `CommandHandler<T>` por tipo. Un comando nuevo es una clase nueva.

| Comando | Comportamiento |
|---|---|
| `REPLICATE_BLOCK` | Lee el `.blk` local y lo envía a `target` con el token del comando. `ALREADY_EXISTS` cuenta como éxito |
| `DELETE_BLOCK` | Borra `.blk` y `.meta`, y publica `block.deleted`, también si no existía |
| `RESYNC` | Publica registro y block report completo |

Un comando repetido no debe cambiar el resultado. Un fallo transitorio lanza una excepción para que el mensaje se reentregue; uno malformado lanza `AmqpRejectAndDontRequeueException`.

Una `REPLICATE_BLOCK` lenta no debe bloquear otros comandos: ejecútala en un ejecutor acotado y confirma el mensaje al terminar. Limita a 50 MB/s con un token bucket propio, `RateLimiter`, compartido entre transferencias de reparación.

### Scrubber

`ScrubberService` recorre los bloques en cada intervalo, a velocidad limitada. Recalcula el SHA-256 del `.blk` y compara con `.meta`. Si difiere: `store.ponerEnCuarentena(key)` y `block.corrupt`. Si el bloque se borra durante el recorrido, ignóralo. No verifica `.part`.

## 7. Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY proto/ proto/
COPY nodes/ nodes/
WORKDIR /src/nodes
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --user-group dfs && mkdir -p /data && chown dfs:dfs /data
COPY --from=build /src/nodes/build/libs/*-SNAPSHOT.jar /app/app.jar
USER dfs
VOLUME /data
EXPOSE 50051 50052
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Ejecución en producción, en la VM de cada nodo:

```bash
docker run -d --name dn-1 --restart unless-stopped \
  -p 50051:50051 -p 50052:50052 \
  -e DFS_NODE_ID=dn-1 -e DFS_NODE_AZ=us-east-1a \
  -e DFS_ADVERTISED_HOST=10.0.1.21 -e DFS_RABBIT_HOST=10.0.1.10 \
  -e DFS_RABBIT_PASS="$DFS_RABBIT_PASS" \
  -v /etc/dfsha/tls:/tls:ro -v /var/lib/dfsha:/data \
  dfsha/node:dev
```

## 8. Pruebas y lista de entrega

Tests obligatorios. Prueba la cadena con bloques de varios MiB, mayores que la ventana de flujo: con bloques pequeños los errores de control de flujo no aparecen.

| Área | Casos |
|---|---|
| Almacén | Commit atómico; hash incorrecto no deja `.blk`; `.part` limpio al arrancar; `blockId` con `../` rechazado |
| gRPC | Con `InProcessServerBuilder`: escritura, lectura por rango, mensajes fuera de orden, CRC incorrecto |
| Cadena | Dos nodos en proceso: 2 recibos; fallo del segundo: `ABORTED`, trailer correcto y sin `.part` en el primero |
| Tokens | Vencido, firma incorrecta, `aud` incorrecto, `ops` insuficiente, bloque ajeno, otra generación |
| Recibos | Firma verificable con el certificado |
| Comandos | Cada comando repetido dos veces da el mismo resultado |
| Outbox | Sobrevive a reinicios; borra solo tras confirmación |
| Scrubber | Detecta un byte alterado y dispara `block.corrupt` |

Comprobación sobre el sistema en marcha:

- [ ] Una escritura deja el bloque como `.blk` en dos nodos y devuelve dos recibos.
- [ ] `kill -9` a mitad de escritura no deja ningún `.blk` incompleto.
- [ ] Una escritura de 128 MiB no hace crecer la memoria en proporción al bloque.
- [ ] Sin RabbitMQ el nodo sigue sirviendo bloques; al volver, vacía el outbox y envía su reporte.
- [ ] TLS 1.2 y texto plano fallan contra `:50051` y `:50052`.
