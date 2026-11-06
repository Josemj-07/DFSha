# DFSha

Sistema de archivos distribuido con bloques inmutables de 128 MiB replicados en 2 de 3 DataNodes. El cliente pide metadatos al servidor y transfiere los bytes directamente con los nodos.

Este documento adapta `DFSha.pdf` a las decisiones del equipo, define lo que comparten las tres partes y fija las reglas de construcción de la [sección 13](#13-reglas-de-construcción). **Si algo de aquí contradice al PDF, manda este documento.**

Personas y agentes de IA construyen con los mismos documentos. Orden de lectura: este README, luego el de tu parte, y el PDF solo para semántica que ninguno de los dos cubra.

| Parte | Documento de construcción |
|---|---|
| Cliente: SDK y CLI `dfs` | [client/README.md](client/README.md) |
| Servidor: metadatos, CA, RabbitMQ | [server/README.md](server/README.md) |
| Nodos esclavos (DataNodes) | [nodes/README.md](nodes/README.md) |

## 1. Cambios frente al PDF

| Tema | PDF | Decisión actual |
|---|---|---|
| Servidores de metadatos | 2 MDS activo/standby | **1 servidor sin replicación.** Solo se replican los bloques de archivos. |
| Testigos y arbitraje | Lease, votos, épocas (sección 7.2) | Eliminados. No existen `WitnessService`, `MetaReplication` ni `503 NOT_ACTIVE`. |
| Edit log | Replicado de forma síncrona | Solo local: WAL + snapshots con `fsync`. |
| Heartbeats | gRPC, fuera de RabbitMQ | **Por RabbitMQ/AMQP.** |
| Registro y block reports | `ControlService` gRPC | También por AMQP. **El servidor no expone gRPC.** |
| Cifrado interno | mTLS entre servidores | **TLS sin certificado de cliente.** gRPC nunca va en texto plano. |
| Identidad de quien llama | Certificado mTLS | JWT (usuario), block token (gRPC), usuario y contraseña (AMQP). |
| Fencing token | `época:secuencia` | Secuencia monótona, persistida en el WAL. |
| Métodos RPC | `WriteBlock`, `ReadBlock`, `ReplicateBlock` | `EscribirBloque`, `LeerBloque`, `ReplicarBloque`, por la regla de [nombres de métodos](#134-estilo-java). |
| `fileSha256` | SHA-256 del archivo | **SHA-256 de los SHA-256 de los bloques, en orden.** Permite calcularlo con E/S paralela y en escrituras parciales sin releer el archivo. |

Se agregan tres elementos que el PDF no detalla: `503 SAFE_MODE` tras reiniciar el servidor, endpoints `/admin` y `POST /locks/{id}:renew`.

**Limitación declarada:** el servidor es un punto único de falla. Si cae, no hay operaciones de metadatos hasta que reinicie. Los bloques siguen seguros en los nodos y el estado se recupera del WAL. Las garantías G1–G5 del PDF se mantienen sobre el servidor único; G6 aplica solo a los datos.

## 2. Reparto de trabajo

| Integrante | Directorio | Entrega | Piezas compartidas a su cargo |
|---|---|---|---|
| **Cliente** | `client/` | SDK, CLI `dfs`, modo `dfs shell`, primitivas RF3 | Revisa el `.proto` y la API REST |
| **Servidor** | `server/` | API REST, autenticación, espacio de nombres, WAL, locks, asignación, Cluster Manager | `server/pki/` (CA) y `server/rabbitmq/` |
| **Nodos** | `nodes/` | Almacén de bloques, gRPC, cadena de réplica, scrubber, agente AMQP | `proto/`, `docker-compose.yml`, `.env.example`, `.dockerignore`, `.gitignore` |

Cada quien trabaja en paralelo con dobles de prueba: el cliente con un servidor gRPC en proceso y un servidor HTTP falso; el servidor publicando mensajes de nodo falsos en RabbitMQ; los nodos generando tokens de prueba con la clave de desarrollo.

## 3. Arquitectura

```
                    HTTPS TLS 1.3 :8443 (JWT)
   Cliente ───────────────────────────────────► Servidor (MDS + Cluster Manager)
   SDK + CLI                                      │ WAL + snapshots
      │                                           │ AMQPS :5671
      │ gRPC TLS 1.3 :50051                       ▼
      │ (block token)                          RabbitMQ
      ▼                                           ▲ heartbeats, registro, reportes,
   DataNode R1 ──gRPC TLS :50052──► DataNode R2   │ eventos / comandos
      └───────────────────────────────┴───────────┘
```

| Canal | Protocolo y puerto | Quién valida a quién |
|---|---|---|
| Cliente → Servidor | HTTPS TLS 1.3, `:8443` | El cliente valida el certificado con la CA; el servidor valida el JWT |
| Cliente → DataNode | gRPC TLS 1.3, `:50051` | El cliente valida el certificado; el nodo valida el block token |
| DataNode → DataNode | gRPC TLS 1.3, `:50052` | El nodo emisor valida el certificado; el receptor valida el block token |
| Servidor y DataNodes → RabbitMQ | AMQP 0-9-1 sobre TLS 1.3, `:5671` | Cada uno valida el broker; el broker valida usuario y permisos |

## 4. Contratos compartidos

Cambiar un contrato requiere acuerdo de los integrantes afectados, y se cambia aquí primero.

### 4.1 gRPC: `proto/dfsha/v1/block.proto`

```proto
syntax = "proto3";

package dfsha.v1;

option java_multiple_files = true;
option java_package = "co.edu.upb.dfsha.proto.v1";

// Puerto 50051. Lo usa el cliente.
service BlockService {
  rpc EscribirBloque(stream WriteRequest) returns (WriteResponse);
  rpc LeerBloque(ReadRequest) returns (stream Chunk);
}

// Puerto 50052. Solo entre DataNodes: cadena de escritura y re-replicación.
service ReplicationService {
  rpc ReplicarBloque(stream WriteRequest) returns (WriteResponse);
}

// Orden del stream: un header, N chunks, un trailer.
message WriteRequest {
  oneof payload {
    WriteHeader header = 1;
    Chunk chunk = 2;
    WriteTrailer trailer = 3;
  }
}

message WriteHeader {
  string block_id = 1;
  int64 gen = 2;
  int64 length = 3;
  repeated Downstream downstream = 4; // Nodos restantes de la cadena; vacío en el último.
}

message Downstream {
  string node_id = 1;
  string addr = 2; // host:50052
}

message Chunk {
  int64 offset = 1;  // Offset dentro del bloque.
  bytes data = 2;    // Máximo 1 MiB.
  fixed32 crc32c = 3;
}

message WriteTrailer {
  string sha256 = 1; // Hexadecimal, del bloque completo.
}

message Receipt {
  string node_id = 1;
  string block_id = 2;
  int64 gen = 3;
  int64 length = 4;
  string sha256 = 5;
  bytes signature = 6;
}

message WriteResponse {
  repeated Receipt receipts = 1; // Un recibo por nodo de la cadena.
}

message ReadRequest {
  string block_id = 1;
  int64 gen = 2;
  int64 offset = 3;
  int64 length = 4; // 0 significa hasta el final del bloque.
}
```

El block token viaja en el metadata `authorization: Bearer <token>`.

| Código gRPC | Significado |
|---|---|
| `UNAUTHENTICATED`, `PERMISSION_DENIED` | Token ausente, vencido o bloque fuera de su alcance |
| `NOT_FOUND`, `FAILED_PRECONDITION` | Bloque o generación inexistente u obsoleta |
| `ALREADY_EXISTS` | Ese `(blockId, gen)` ya está completo en el nodo |
| `DATA_LOSS` | Checksum incorrecto; la descripción incluye el offset |
| `ABORTED` | Falló un nodo de la cadena; el trailer `dfs-failed-nodes` trae sus IDs separados por coma |
| `RESOURCE_EXHAUSTED` | Disco lleno |
| `UNAVAILABLE`, `DEADLINE_EXCEEDED` | Nodo caído o sin respuesta en 5 s |

### 4.2 API REST: `https://<servidor>:8443/v1`

Todo requiere `Authorization: Bearer <jwt>` salvo `login` y `refresh`.

| Método y ruta | Descripción | Errores típicos |
|---|---|---|
| `POST /auth/login` | Devuelve access token (15 min) y refresh token (8 h) | `401 INVALID_CREDENTIALS`, `429 TOO_MANY_ATTEMPTS` |
| `POST /auth/refresh`, `POST /auth/logout` | Rota o revoca el refresh token | `401 INVALID_TOKEN` |
| `GET /fs/ls?path=&cursor=`, `GET /fs/stat?path=` | Lista paginada y atributos | `403 FORBIDDEN`, `404 NOT_FOUND` |
| `POST /fs/mkdir`, `DELETE /fs/rmdir?path=&recursive=` | Crea o borra un directorio | `409 ALREADY_EXISTS`, `409 DIR_NOT_EMPTY` |
| `DELETE /fs/files?path=`, `POST /fs/move`, `POST /fs/copy` | Borrado lógico, renombrado atómico, copia COW | `404`, `409`, `507 QUOTA_EXCEEDED` |
| `POST /files:open` | Abre en modo `r`, `w`, `a` o `rw` | `404`, `409 LOCK_CONFLICT` |
| `POST /files/{h}:allocate` | Asigna bloques, pipeline y `writeToken` | `503 INSUFFICIENT_NODES`, `410 HANDLE_EXPIRED` |
| `POST /files/{h}:recover-block` | Regenera el pipeline con `gen + 1` | `503 INSUFFICIENT_NODES` |
| `POST /files/{h}:commit` | Publica la versión de forma atómica | `409 VERSION_CONFLICT`, `409 STALE_FENCING_TOKEN`, `422 BLOCK_NOT_DURABLE` |
| `POST /files/{h}:keepalive` | Renueva el lease y, en lectura, el `readToken` | `410 HANDLE_EXPIRED` |
| `POST /files/{h}:close` | Libera el handle sin publicar nada nuevo | `410 HANDLE_EXPIRED` |
| `POST /locks`, `POST /locks/{id}:renew`, `DELETE /locks/{id}` | Adquiere, renueva o libera un lock | `409 LOCK_CONFLICT` |
| `POST /reports/replica` | Reporta una réplica inalcanzable o corrupta | |
| `POST /admin/users`, `PUT /admin/users/{u}/quota`, `GET /admin/nodes`, `GET /admin/audit` | Solo rol `ADMIN` | `403 FORBIDDEN` |

Cualquier ruta de `/files` o `/locks` puede responder `503 SAFE_MODE` tras un reinicio; el SDK reintenta con espera.

Los errores usan `application/problem+json` (RFC 9457):

```json
{"type":"/errors/stale-fencing-token","title":"Stale fencing token","status":409,"code":"STALE_FENCING_TOKEN","detail":"reemplazado por 88124","traceId":"7f3a"}
```

Ejemplo de escritura:

```json
POST /v1/files:open
{"path":"/home/jose/ventas.csv","mode":"w","create":true,"exclusive":false}
200
{"handle":"h_9f2c","baseVersion":6,"blockSize":134217728,"size":0,"fileSha256":null,
 "lease":{"fencingToken":"88120","ttlMs":30000},"readToken":null,"blocks":[]}

POST /v1/files/h_9f2c:allocate
{"firstIdx":0,"count":2}
200
{"writeToken":"eyJ...","blocks":[{"idx":0,"blockId":"blk_01JBX4","gen":1,"pipeline":[
  {"nodeId":"dn-1","clientAddr":"10.0.1.21:50051","replicationAddr":"10.0.1.21:50052"},
  {"nodeId":"dn-2","clientAddr":"10.0.2.37:50051","replicationAddr":"10.0.2.37:50052"}]}]}

POST /v1/files/h_9f2c:commit
{"fencingToken":"88120","size":201326592,"fileSha256":"9b1d...","blocks":[
  {"idx":0,"blockId":"blk_01JBX4","gen":1,"len":134217728,"sha256":"a3f0...",
   "receipts":[{"node":"dn-1","sig":"<base64>"},{"node":"dn-2","sig":"<base64>"}]}]}
200
{"version":7}
```

Ejemplo de lectura:

```json
POST /v1/files:open
{"path":"/home/jose/ventas.csv","mode":"r"}
200
{"handle":"h_7a10","baseVersion":7,"blockSize":134217728,"size":201326592,"fileSha256":"9b1d...",
 "lease":{"fencingToken":null,"ttlMs":30000},"readToken":"eyJ...",
 "blocks":[{"idx":0,"blockId":"blk_01JBX4","gen":1,"len":134217728,"sha256":"a3f0...",
   "replicas":[{"nodeId":"dn-1","addr":"10.0.1.21:50051","az":"us-east-1a"},
               {"nodeId":"dn-2","addr":"10.0.2.37:50051","az":"us-east-1b"}]}]}
```

Cuerpos de las demás rutas. El cliente ya los usa así: **el servidor debe respetar estos nombres de campo**.

| Ruta | Petición | Respuesta |
|---|---|---|
| `/auth/login` | `{"username","password"}` | `{"accessToken","refreshToken","expiresIn"}`, con `expiresIn` en segundos |
| `/auth/refresh` | `{"refreshToken"}` | Igual que login |
| `/auth/logout` | `{"refreshToken"}` | `204` |
| `/fs/ls` | | `{"entries":[<atributos>],"nextCursor"}`, con `nextCursor` nulo en la última página |
| `/fs/stat` | | `{"name","path","type","owner","mode","size","version","mtime"}`, con `type` `FILE` o `DIR`, `mode` como `rwxr-x---` y `mtime` en ISO-8601 |
| `/fs/mkdir` | `{"path","parents"}` | `204` |
| `/fs/move`, `/fs/copy` | `{"from","to"}` | `204` |
| `:allocate` | `{"firstIdx","count"}`, con índices consecutivos | Como en el ejemplo |
| `:recover-block` | `{"idx","failedNodes":[]}` | Igual que `:allocate`, con un solo bloque |
| `:keepalive` | | `{"ttlMs","readToken"}`, con `readToken` nulo en modo `w` |
| `:close` | | `204` |
| `/locks` | `{"path","mode","range":{"first","last"},"ttlMs","waitMs"}`, con `mode` `SHARED` o `EXCLUSIVE` y `range` nulo para todo el archivo | `{"lockId","fencingToken","expiresAt"}` |
| `/locks/{id}:renew` | | `204` |
| `/reports/replica` | `{"blockId","gen","node","reason"}`, con `reason` `UNREACHABLE` o `CORRUPT` | `204` |
| `/admin/users` | `{"username","password","role"}`, con `role` `USER` o `ADMIN` | `204` |
| `/admin/users/{u}/quota` | `{"quotaBytes"}` | `204` |
| `/admin/nodes` | | `[{"nodeId","addr","az","status","capacityBytes","usedBytes"}]` |
| `/admin/audit` | | `[{"time","user","action","ip","result","traceId"}]` |

Reglas de `:open` y `:commit`:

- En modo `a` y `rw`, `:open` devuelve `blocks` y `readToken` como en lectura, porque el cliente necesita leer para el copy-on-write. Solo `w` devuelve `blocks` vacío.
- `:commit` admite solo los bloques modificados. El mapa final es el vigente con esos bloques superpuestos. En modo `w` la base es un mapa vacío.
- Un handle puede publicar varias veces, una por cada `flush`. Tras cada commit, la `baseVersion` del handle avanza a la versión creada.
- `fencingToken` es el del lease en `w` y `a`, y el del lock en `rw`.
- En `rw` el cliente envía `fileSha256` nulo y el servidor calcula el hash y el tamaño del mapa final. Con escritores en rangos disjuntos, ningún cliente conoce el resultado: si lo calculara, el servidor rechazaría commits legítimos.

### 4.3 AMQP

Vhost `dfsha`. Toda la topología la crea `definitions.json` ([server/README.md](server/README.md#5-rabbitmq)). **Ninguna aplicación declara colas ni exchanges.**

| Routing key en `dfs.events` (topic) | Cola | Cuerpo JSON |
|---|---|---|
| `node.heartbeat.<id>` cada 3 s | `mds.heartbeats` (TTL 5 s) | `{"nodeId","capacityBytes","usedBytes","activeConnections","blockCount","sentAt"}` |
| `node.register.<id>` | `mds.events` | `{"nodeId","host","clientPort","replicationPort","az","capacityBytes","certPem"}` |
| `node.report.<id>` | `mds.events` | `{"nodeId","blocks":[{"blockId","gen","length","sha256"}]}` |
| `block.received.<id>`, `block.deleted.<id>`, `block.corrupt.<id>` | `mds.events` | `{"eventId","nodeId","blockId","gen","length","sha256"}` |

El exchange `dfs.commands` (direct) entrega a `dn.<id>.commands` con routing key `<id>`. Los comandos son idempotentes por `commandId`:

```json
{"commandId":"c-7f3a","type":"REPLICATE_BLOCK","blockId":"blk_01JBX4","gen":1,"target":{"nodeId":"dn-3","addr":"10.0.3.14:50052"},"token":"eyJ..."}
{"commandId":"c-7f3b","type":"DELETE_BLOCK","blockId":"blk_01JBX4","gen":1}
{"commandId":"c-7f3c","type":"RESYNC"}
```

`RESYNC` pide al nodo publicar de nuevo su registro y su block report completo.

**Identidad.** Cada nodo publica con la propiedad AMQP `user_id` igual a su ID. RabbitMQ rechaza el mensaje si no coincide con el usuario de la conexión. El servidor descarta todo mensaje cuyo `user_id`, el `<id>` de la routing key y el `nodeId` del cuerpo no coincidan.

### 4.4 Tokens y recibos

| Elemento | Algoritmo | Contenido |
|---|---|---|
| Access token de usuario | JWT EdDSA, clave `jwt.key`, 15 min | `sub`, `role`, `exp`, `iat`, `jti` |
| Block token | JWT EdDSA, clave `blocktoken.key`, 10 min | `sub`, `aud=dfsha-datanode`, `ops=r\|w`, `blk=["<blockId>:<gen>"]`, `exp` |
| Recibo de bloque | SHA256withECDSA con la clave TLS del nodo | Firma de `dfsha-receipt-v1\|<blockId>\|<gen>\|<length>\|<sha256>\|<nodeId>` en UTF-8 |
| `fileSha256` | SHA-256 | Sobre la concatenación de los 32 bytes de cada SHA-256 de bloque, en orden de `idx` |

Vector de prueba de `fileSha256`, para que cliente y servidor coincidan: dos bloques con el contenido `hola` y `mundo` dan `c030ed83e4b337813e905c456a45c002790bc315b88ec9d47962b8563d502803`. Un archivo vacío da `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

## 5. Seguridad

La CA vive en la VM del servidor. Un cliente que confía solo en `ca.crt` rechaza cualquier servidor o nodo que no presente un certificado firmado por ella y emitido para esa IP o nombre.

| Material | Servidor | RabbitMQ | DataNode | Cliente |
|---|---|---|---|---|
| `ca.key` | Solo en el host, nunca en un contenedor | | | |
| `ca.crt` | Sí | Sí | Sí | Sí, es lo único que necesita |
| `tls.key`, `tls.crt` propios | Sí | Sí | Sí | No |
| `jwt.key`, `blocktoken.key` | Sí | | | |
| `blocktoken.pub` | Sí | | Sí | |

Reglas:

- Las claves y los certificados se montan como volúmenes de solo lectura. Nunca entran a la imagen ni al repositorio.
- Solo TLS 1.3. No hay puertos en texto plano ni opción para desactivar la verificación.
- El SAN del certificado debe contener la dirección exacta con la que se conecta el otro extremo. La CA decide los SAN al firmar.

El flujo para emitir certificados está en [server/README.md](server/README.md#4-autoridad-certificadora).

## 6. Estructura del repositorio

```
DFSha/
├── docker-compose.yml
├── .env.example
├── .dockerignore
├── .gitignore
├── proto/dfsha/v1/block.proto
├── client/    Dockerfile, README.md, proyecto Gradle
├── server/    Dockerfile, README.md, proyecto Gradle, pki/, rabbitmq/
└── nodes/     Dockerfile, README.md, proyecto Gradle
```

El contexto de construcción de Docker es la raíz del repositorio, porque `client` y `nodes` necesitan `proto/`.

`.gitignore`:

```gitignore
.env
**/build/
**/.gradle/
*.key
*.crt
*.csr
*.srl
pki-out/
```

`.dockerignore`:

```
.git
**/build
**/.gradle
.env
*.pdf
```

## 7. Docker Compose (pruebas locales)

Requiere Docker Engine 26 o superior, por el uso de `subpath` en volúmenes. El servicio `pki` crea la CA y los secretos de cada servicio en un volumen, y cada contenedor monta solo su subdirectorio.

`.env.example` (cópialo a `.env` y cambia los valores):

```dotenv
RABBIT_MDS_PASS=cambiar-mds
RABBIT_DN1_PASS=cambiar-dn1
RABBIT_DN2_PASS=cambiar-dn2
RABBIT_DN3_PASS=cambiar-dn3
DFS_ADMIN_PASSWORD=cambiar-admin-12-caracteres
```

`docker-compose.yml`:

```yaml
name: dfsha

x-node: &node
  build:
    context: .
    dockerfile: nodes/Dockerfile
  image: dfsha/node:dev
  restart: unless-stopped
  depends_on:
    rabbitmq:
      condition: service_healthy

services:
  pki:
    image: alpine:3.22
    command: ["sh", "-c", "apk add --no-cache openssl >/dev/null && sh /repo/server/pki/gen-dev-pki.sh"]
    environment:
      RABBIT_MDS_PASS: ${RABBIT_MDS_PASS}
      RABBIT_DN1_PASS: ${RABBIT_DN1_PASS}
      RABBIT_DN2_PASS: ${RABBIT_DN2_PASS}
      RABBIT_DN3_PASS: ${RABBIT_DN3_PASS}
    volumes:
      - ./server:/repo/server:ro
      - dev-secrets:/dev-secrets

  rabbitmq:
    image: rabbitmq:4.3-alpine
    depends_on:
      pki:
        condition: service_completed_successfully
    volumes:
      - ./server/rabbitmq/rabbitmq.conf:/etc/rabbitmq/rabbitmq.conf:ro
      - type: volume
        source: dev-secrets
        target: /secrets
        read_only: true
        volume:
          subpath: rabbitmq
      - rabbitmq-data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "check_running"]
      interval: 5s
      timeout: 10s
      retries: 20

  server:
    build:
      context: .
      dockerfile: server/Dockerfile
    image: dfsha/server:dev
    restart: unless-stopped
    depends_on:
      rabbitmq:
        condition: service_healthy
    environment:
      DFS_TLS_DIR: /tls
      DFS_DATA_DIR: /data
      DFS_RABBIT_HOST: rabbitmq
      DFS_RABBIT_PASS: ${RABBIT_MDS_PASS}
      DFS_ADMIN_PASSWORD: ${DFS_ADMIN_PASSWORD}
    volumes:
      - type: volume
        source: dev-secrets
        target: /tls
        read_only: true
        volume:
          subpath: server
      - server-data:/data
    ports:
      - "8443:8443"

  dn-1:
    <<: *node
    environment:
      DFS_NODE_ID: dn-1
      DFS_NODE_AZ: az-a
      DFS_ADVERTISED_HOST: dn-1
      DFS_TLS_DIR: /tls
      DFS_DATA_DIR: /data
      DFS_RABBIT_HOST: rabbitmq
      DFS_RABBIT_PASS: ${RABBIT_DN1_PASS}
    volumes:
      - type: volume
        source: dev-secrets
        target: /tls
        read_only: true
        volume:
          subpath: dn-1
      - dn1-data:/data

  dn-2:
    <<: *node
    environment:
      DFS_NODE_ID: dn-2
      DFS_NODE_AZ: az-b
      DFS_ADVERTISED_HOST: dn-2
      DFS_TLS_DIR: /tls
      DFS_DATA_DIR: /data
      DFS_RABBIT_HOST: rabbitmq
      DFS_RABBIT_PASS: ${RABBIT_DN2_PASS}
    volumes:
      - type: volume
        source: dev-secrets
        target: /tls
        read_only: true
        volume:
          subpath: dn-2
      - dn2-data:/data

  dn-3:
    <<: *node
    environment:
      DFS_NODE_ID: dn-3
      DFS_NODE_AZ: az-c
      DFS_ADVERTISED_HOST: dn-3
      DFS_TLS_DIR: /tls
      DFS_DATA_DIR: /data
      DFS_RABBIT_HOST: rabbitmq
      DFS_RABBIT_PASS: ${RABBIT_DN3_PASS}
    volumes:
      - type: volume
        source: dev-secrets
        target: /tls
        read_only: true
        volume:
          subpath: dn-3
      - dn3-data:/data

  client:
    build:
      context: .
      dockerfile: client/Dockerfile
    image: dfsha/client:dev
    profiles: ["cli"]
    environment:
      DFS_SERVER_URL: https://server:8443
      DFS_CA_CERT: /tls/ca.crt
    volumes:
      - type: volume
        source: dev-secrets
        target: /tls
        read_only: true
        volume:
          subpath: client
      - client-home:/home/dfs

volumes:
  dev-secrets:
  rabbitmq-data:
  server-data:
  dn1-data:
  dn2-data:
  dn3-data:
  client-home:
```

Uso:

```bash
cp .env.example .env
docker compose --profile cli build
docker compose up -d
docker compose --profile cli run --rm client shell
docker compose down        # agrega -v para borrar datos y secretos
```

## 8. Producción

Las imágenes son las mismas: nada del entorno va dentro de ellas. Cada contenedor corre en su VM con `docker run`, y solo cambian las variables `DFS_*`, los volúmenes y los certificados, que se emiten con la IP privada real en el SAN.

El despliegue del cliente está en [client/PRODUCCION.md](client/PRODUCCION.md).

## 9. Fallas esperadas

| Falla | Detección | Efecto y recuperación |
|---|---|---|
| DataNode caído | Sin heartbeats: `SUSPECT` a 10 s, `DEAD` a 30 s | Lectura por la otra réplica. Escritura con `recover-block`. A los 2 min el servidor re-replica. |
| Réplica corrupta | CRC32C, SHA-256 o scrubber | El cliente usa la otra réplica. El servidor la sana y borra la corrupta. |
| Servidor caído | El cliente no conecta | Sin metadatos. Las transferencias en curso siguen mientras su token sea válido. Al reiniciar: WAL, `SAFE_MODE` y block reports. |
| RabbitMQ caído | Conexión AMQP rota | **El detector de fallas se congela** para no declarar muertos a todos los nodos. Los nodos guardan eventos en un outbox y al reconectar envían un block report. |
| Cliente caído | Lease vencido a los 30 s | Lock liberado, sesión abortada, bloques huérfanos a GC tras 1 h. |

## 10. Hitos de integración

| Hito | Resultado verificable |
|---|---|
| H0 Contratos | `proto/`, compose, PKI de desarrollo y `definitions.json` en `main`; `docker compose up` levanta todo |
| H1 Esqueleto seguro | `dfs login` por HTTPS; heartbeats visibles en el servidor; lectura gRPC de prueba con token |
| H2 Transferencia | `dfs put` y `dfs get` de un archivo de varios bloques con RF = 2 y hash idéntico |
| H3 Espacio de nombres | `ls`, `mkdir`, `mv`, `cp`, `rm`, reinicio del servidor sin perder datos |
| H4 Concurrencia y RF3 | `open/read/write/flush/close`, `lock`, 409 en conflictos |
| H5 Fallas | Detener un nodo durante `put` y `get`; re-replicación; GC; detener RabbitMQ |
| H6 Seguridad | Las pruebas de la siguiente sección pasan |

## 11. Pruebas de seguridad

```bash
# Con TLS 1.2 debe fallar y con TLS 1.3 debe conectar.
curl --cacert ca.crt --tls-max 1.2 https://localhost:8443/
curl --cacert ca.crt --tlsv1.3 https://localhost:8443/actuator/health
```

| Caso | Resultado esperado |
|---|---|
| Cliente con otra CA en `DFS_CA_CERT` | Falla el handshake con el servidor y con los nodos |
| gRPC en texto plano contra `:50051` | Falla |
| gRPC con token inválido o de otro bloque | `UNAUTHENTICATED` o `PERMISSION_DENIED` |
| `dn-1` publica con `user_id=dn-2` | RabbitMQ cierra el canal con `406 PRECONDITION_FAILED` |
| `dn-1` publica en `dfs.commands` | RabbitMQ cierra el canal con `403 ACCESS_REFUSED` |
| Captura de tráfico con `tcpdump` | Contenido ilegible |

## 12. Versiones verificadas

Estas versiones se compilaron y probaron juntas el 2026-11-06 con un prototipo desechable que cubrió HTTPS, gRPC con TLS, RabbitMQ con TLS y permisos, tokens EdDSA, recibos, Argon2id y este compose con un nodo. El sistema completo todavía no existe. Cambiar una versión exige repetir esas pruebas.

| Componente | Versión |
|---|---|
| Java (Temurin) | 21 |
| Gradle (wrapper) | 9.8.2 |
| Spring Boot | 4.1.2 |
| `io.spring.dependency-management` | 1.1.7 |
| `com.google.protobuf` (plugin Gradle) | 0.10.1 |
| gRPC Java | 1.85.1 |
| Protobuf | 4.36.2 |
| JJWT | 0.13.0 |
| BouncyCastle `bcprov-jdk18on` | 1.86 |
| Picocli | 4.7.7 |
| Jackson (solo cliente) | 2.20.1 |
| JLine | 3.30.6 |
| JUnit (solo pruebas) | 5.13.4 |
| BouncyCastle `bcpkix-jdk18on` (solo pruebas) | 1.86 |
| RabbitMQ | `rabbitmq:4.3-alpine` |

## 13. Reglas de construcción

Aplican a todo el repositorio.

### 13.1 Prohibido implementar

- Un segundo servidor de metadatos, replicación del edit log, testigos, votos, épocas, `WitnessService`, `MetaReplication`, `503 NOT_ACTIVE`.
- Servicios gRPC en el servidor, o `ControlService`.
- mTLS o certificados de cliente.
- Puertos en texto plano, TLS anterior a 1.3, o cualquier opción que omita la verificación de certificados o de nombre.
- Declaración de colas, exchanges o bindings desde código.
- Infraestructura AWS, Terraform o redes. Solo código, Dockerfiles y Compose.

### 13.2 Arquitectura y diseño

Arquitectura hexagonal (puertos y adaptadores):

- `domain` no importa Spring, gRPC, AMQP, Jackson ni `java.net`.
- Los casos de uso orquestan el dominio mediante puertos.
- Los adaptadores traducen entre el exterior y el dominio. Las clases generadas por protobuf y los DTO no entran al dominio.
- Las dependencias apuntan hacia adentro y no hay ciclos entre paquetes.

SOLID se aplica para que el código sea fácil de cambiar, no para añadir capas vacías:

| Principio | Aplicación concreta |
|---|---|
| Responsabilidad única | Una clase, una razón de cambio. Un controlador traduce HTTP; no contiene reglas. |
| Abierto/cerrado | Comandos AMQP, comandos de CLI y operaciones del WAL se agregan con clases nuevas, no con `if` largos. |
| Sustitución de Liskov | Todo doble de prueba de un puerto cumple el mismo contrato que la implementación real. |
| Segregación de interfaces | Puertos pequeños. Nunca una interfaz `Service` con veinte métodos. |
| Inversión de dependencias | Dominio y casos de uso dependen de interfaces. Los adaptadores las implementan. |

Patrones que encajan, donde resuelvan un problema real: **Strategy** (ubicación de réplicas, despacho de comandos, reintentos), **Command** (operaciones del WAL, comandos de la CLI), **Facade** (`DfsClient`), **Adapter** (gateways REST y gRPC, almacenamiento, AMQP) y **Outbox** (eventos del DataNode con el broker caído).

### 13.3 Partición de archivos

- Un tipo público por archivo. El nombre describe el rol: `FileSystemBlockStore`, no `Manager2`.
- Objetivo: menos de 200 líneas por clase. Pasar de 300 suele indicar dos responsabilidades: divídela.
- Métodos de unas 30 líneas como máximo, con cláusulas de guarda.
- Paquetes por funcionalidad, no por tipo técnico global. Prohibidos `utils`, `helpers` y `misc`.
- Los tests replican el paquete del código que prueban.
- Sin código comentado ni `TODO` sin explicación.

### 13.4 Estilo Java

- Java 21: `record` para datos, `sealed` para jerarquías cerradas, `switch` con patrones.
- Inyección por constructor. Nunca `@Autowired` en campos.
- Inmutabilidad por defecto. El estado mutable compartido se protege y se documenta.
- Configuración con `record` y `@ConfigurationProperties`, sin `@Value` disperso. Ningún valor de entorno va en el código.
- Inyecta `java.time.Clock` y las fuentes de aleatoriedad, para poder probar tiempos y decisiones al azar.
- Excepciones de dominio específicas, traducidas a HTTP o gRPC en un único lugar. No ignores excepciones.
- Comentarios solo para el porqué. Mensajes al usuario y comentarios en español.
- **Los nombres de métodos van en español**, con un verbo en infinitivo: `abrir`, `confirmar`, `obtenerTamanio`, `estaConectado`, `tieneEspacio`. Sin tildes ni ñ, y la ñ se escribe `ni`. Clases, paquetes, variables y contratos siguen en inglés.
- Conservan su nombre los métodos impuestos desde fuera: los de JDK, Spring, gRPC y Picocli que se sobrescriben (`close`, `onNext`, `interceptCall`, `call`, `stop`) y los accesores de `record` cuyo nombre sale de un campo del contrato (`commandId()`).
- Logs con SLF4J. **Nunca registres contraseñas, tokens, claves o contenido de archivos.**
- Valida toda entrada externa, aunque venga de otro componente del sistema.
- Compara firmas y hashes con funciones de tiempo constante, como `MessageDigest.isEqual`, y usa `SecureRandom` para tokens y secretos.

### 13.5 Errores conocidos

Cada uno ocurrió de verdad al validar esta pila:

| Síntoma | Causa | Solución |
|---|---|---|
| HTTPS acepta TLS 1.2 | Con `server.ssl.bundle` se ignora `server.ssl.enabled-protocols` | `spring.ssl.bundle.pem.<nombre>.options.enabled-protocols=TLSv1.3` |
| `NoClassDefFoundError: tools/jackson/...` | Proyecto sin web: el starter de AMQP no trae Jackson 3 | `spring-boot-starter-jackson` |
| Initializr responde error con `amqp` | El ID actual de la dependencia es `rabbitmq` | `dependencies=rabbitmq` |
| `docker compose build` no construye el cliente | `client` está en el perfil `cli` | `docker compose --profile cli build` |
| RabbitMQ no lee `tls.key` | En `rabbitmq:4.3-alpine` el usuario es `100:101` | `chown 100:101` |

Spring Boot 4 usa Jackson 3: los paquetes son `tools.jackson.*`, aunque las anotaciones siguen en `com.fasterxml.jackson.annotation`. El cliente usa Jackson 2 porque no depende de Spring.

### 13.6 Definición de terminado

Una tarea está terminada solo si:

1. `./gradlew build` pasa en el directorio afectado.
2. La lógica nueva tiene tests, incluidos los casos de error.
3. La imagen se construye y, si el cambio toca la integración, `docker compose up` funciona.
4. Las casillas aplicables de la lista de entrega de esa parte se comprobaron de verdad.
5. Si cambió un contrato, este README se actualizó primero, junto con todas las partes afectadas.

Reporta lo que no probaste. No digas que algo funciona sin haberlo ejecutado.
