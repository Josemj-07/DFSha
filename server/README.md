# Servidor DFSha

Documento de construcción del servidor de metadatos (MDS), para personas y agentes de IA. Lee primero la [especificación](../docs/especificacion.md): ahí están los contratos, los cambios frente al PDF y las [reglas de construcción](../docs/especificacion.md#13-reglas-de-construcción). Los scripts, la configuración y el Dockerfile de este documento fueron validados: úsalos tal cual.

## 1. Qué te corresponde

- API REST sobre HTTPS, autenticación, permisos y auditoría.
- Espacio de nombres, inodos y mapa de bloques en memoria, durables con WAL y snapshots.
- Asignación de bloques, block tokens, locks con fencing y commit atómico.
- Cluster Manager: detección de fallas por heartbeats AMQP, re-replicación, GC y reconciliación.
- La autoridad certificadora (`server/pki/`) y la configuración de RabbitMQ (`server/rabbitmq/`).

El servidor **nunca** transporta bytes de archivos y **no** expone gRPC.

**Fuera de alcance:** replicación del servidor, base de datos externa, CRL u OCSP, renovación automática de certificados, interfaz web.

### Orden de construcción

Termina y prueba cada paso antes del siguiente.

1. Crea `pki/` y `rabbitmq/`. Comprueba con `docker compose up pki rabbitmq`.
2. Genera el proyecto, agrega dependencias y configura HTTPS. Comprueba que TLS 1.2 falla.
3. `common`: errores `problem+json` y `traceId`.
4. `journal`: WAL, snapshots y recuperación. Es la base de todo.
5. `auth`: usuarios, login, JWT, refresh, límite de intentos.
6. `namespace`: directorios e inodos.
7. `cluster`: registro, heartbeats, detector de fallas.
8. `blocks`: mapa de bloques, ubicación y block tokens.
9. `locks`.
10. `files`: open, allocate, recover-block, commit, close, keepalive.
11. `cluster`: reparación, GC y reconciliación.
12. `audit` y `admin`.

## 2. Crear el proyecto

Desde la raíz del repositorio, o con los mismos valores en [start.spring.io](https://start.spring.io) (Gradle - Groovy, Java 21, Jar):

```bash
curl -sS https://start.spring.io/starter.tgz \
  -d type=gradle-project -d language=java -d bootVersion=4.1.2 -d javaVersion=21 \
  -d groupId=co.edu.upb.dfsha -d artifactId=server -d name=server \
  -d packageName=co.edu.upb.dfsha.server -d baseDir=server \
  -d dependencies=web,security,validation,rabbitmq,actuator,prometheus | tar -xzf -
```

`tar` puede mostrar avisos sobre `LIBARCHIVE.creationtime`; son inofensivos.

Agrega a `dependencies` en `build.gradle`:

```groovy
implementation 'io.jsonwebtoken:jjwt-api:0.13.0'
runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.13.0'
runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.13.0'
implementation 'org.bouncycastle:bcprov-jdk18on:1.86' // Lo exige Argon2PasswordEncoder.
```

## 3. Estructura

Un paquete por módulo funcional. Dentro de cada uno, `api` (HTTP), `application` (casos de uso), `domain` (reglas, sin Spring) e `infra` (adaptadores), según los necesite.

```
server/
├── Dockerfile
├── build.gradle
├── pki/                      Scripts de la CA
├── rabbitmq/                 rabbitmq.conf, definitions.template.json
└── src/main/java/co/edu/upb/dfsha/server/
    ├── ServerApplication.java
    ├── config/               DfsProperties, SecurityConfig, AmqpConfig
    ├── common/               Errores problem+json, traceId
    ├── auth/                 Login, refresh, JWT, Argon2id, límite de intentos
    ├── namespace/            Directorios e inodos, ls, stat, mkdir, mv, cp, rm
    ├── files/                open, allocate, recover-block, commit, close, keepalive
    ├── locks/                Tabla de locks, cola FIFO, fencing
    ├── blocks/               Mapa de bloques, ubicación de réplicas, block tokens
    ├── journal/              WAL, snapshots, recuperación
    ├── cluster/              Registro de nodos, detector, reparación, GC, AMQP
    ├── audit/                Registro de auditoría y uso por usuario
    └── admin/                Usuarios, cuotas, nodos
```

### Configuración

`src/main/resources/application.properties`:

```properties
spring.application.name=server
spring.threads.virtual.enabled=true

server.port=8443
server.ssl.bundle=server
spring.ssl.bundle.pem.server.keystore.certificate=file:${DFS_TLS_DIR:/tls}/tls.crt
spring.ssl.bundle.pem.server.keystore.private-key=file:${DFS_TLS_DIR:/tls}/tls.key
spring.ssl.bundle.pem.server.options.enabled-protocols=TLSv1.3

spring.ssl.bundle.pem.broker.truststore.certificate=file:${DFS_TLS_DIR:/tls}/ca.crt
spring.ssl.bundle.pem.broker.protocol=TLSv1.3
spring.rabbitmq.host=${DFS_RABBIT_HOST}
spring.rabbitmq.port=5671
spring.rabbitmq.virtual-host=dfsha
spring.rabbitmq.username=mds
spring.rabbitmq.password=${DFS_RABBIT_PASS}
spring.rabbitmq.ssl.enabled=true
spring.rabbitmq.ssl.bundle=broker
spring.rabbitmq.publisher-confirm-type=correlated

management.endpoints.web.exposure.include=health,prometheus

dfs.data-dir=${DFS_DATA_DIR:/data}
dfs.tls-dir=${DFS_TLS_DIR:/tls}
dfs.block-size-bytes=${DFS_BLOCK_SIZE_BYTES:134217728}
dfs.admin-password=${DFS_ADMIN_PASSWORD}
```

> **Cuidado:** con `server.ssl.bundle`, la propiedad `server.ssl.enabled-protocols` se ignora y el servidor acepta TLS 1.2. Usa `options.enabled-protocols` del bundle, como arriba.

## 4. Autoridad certificadora

Crea estos scripts en `server/pki/`. Son POSIX `sh` para que también corran en el contenedor Alpine de Compose.

`init-ca.sh`:

```sh
#!/bin/sh
# Crea la CA una sola vez. Uso: PKI_DIR=/etc/dfsha/pki init-ca.sh
set -eu
PKI_DIR="${PKI_DIR:-./pki-out}"
mkdir -p "$PKI_DIR/ca"; chmod 700 "$PKI_DIR/ca"
if [ -f "$PKI_DIR/ca/ca.key" ]; then echo "La CA ya existe en $PKI_DIR/ca"; exit 0; fi
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$PKI_DIR/ca/ca.key"
chmod 600 "$PKI_DIR/ca/ca.key"
openssl req -x509 -new -key "$PKI_DIR/ca/ca.key" -sha256 -days 825 \
  -subj "/O=DFSha/CN=DFSha Root CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash" \
  -out "$PKI_DIR/ca/ca.crt"
```

`gen-csr.sh`:

```sh
#!/bin/sh
# Genera clave privada y CSR. Uso: gen-csr.sh <nombre> <directorio-salida>
set -eu
NAME="$1"; OUT="$2"
mkdir -p "$OUT"
[ -f "$OUT/tls.key" ] || openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$OUT/tls.key"
chmod 600 "$OUT/tls.key"
openssl req -new -key "$OUT/tls.key" -subj "/O=DFSha/CN=$NAME" -out "$OUT/$NAME.csr"
```

`sign-csr.sh`:

```sh
#!/bin/sh
# Firma un CSR. La CA decide los SAN, no el solicitante.
# Uso: sign-csr.sh <archivo.csr> <salida.crt> "DNS:dn-1,IP:10.0.1.20"
set -eu
CSR="$1"; CRT="$2"; SAN="$3"
PKI_DIR="${PKI_DIR:-./pki-out}"
EXT="$(mktemp)"; trap 'rm -f "$EXT"' EXIT
cat > "$EXT" <<EXTEOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
subjectAltName=$SAN
EXTEOF
openssl x509 -req -in "$CSR" -CA "$PKI_DIR/ca/ca.crt" -CAkey "$PKI_DIR/ca/ca.key" \
  -CAcreateserial -days 397 -sha256 -extfile "$EXT" -out "$CRT"
openssl verify -CAfile "$PKI_DIR/ca/ca.crt" "$CRT"
```

`gen-token-keys.sh`:

```sh
#!/bin/sh
# Claves Ed25519: jwt.* firma sesiones y blocktoken.* firma block tokens.
# Uso: gen-token-keys.sh <directorio-salida>
set -eu
OUT="$1"; mkdir -p "$OUT"
for k in jwt blocktoken; do
  [ -f "$OUT/$k.key" ] || openssl genpkey -algorithm ed25519 -out "$OUT/$k.key"
  chmod 600 "$OUT/$k.key"
  openssl pkey -in "$OUT/$k.key" -pubout -out "$OUT/$k.pub"
done
```

`gen-dev-pki.sh` (solo para Compose):

```sh
#!/bin/sh
# Solo para docker compose local. Corre dentro del servicio "pki" como root.
set -eu
HERE="$(dirname "$0")"
export PKI_DIR=/dev-secrets
sh "$HERE/init-ca.sh"
issue() { # nombre uid:gid SAN
  d="$PKI_DIR/$1"
  sh "$HERE/gen-csr.sh" "$1" "$d"
  sh "$HERE/sign-csr.sh" "$d/$1.csr" "$d/tls.crt" "$3"
  cp "$PKI_DIR/ca/ca.crt" "$d/ca.crt"
  chown -R "$2" "$d"
}
issue server   10001:10001 "DNS:server,DNS:localhost,IP:127.0.0.1"
issue rabbitmq 100:101     "DNS:rabbitmq,DNS:localhost,IP:127.0.0.1"
for n in 1 2 3; do issue "dn-$n" 10001:10001 "DNS:dn-$n,DNS:localhost,IP:127.0.0.1"; done
sh "$HERE/gen-token-keys.sh" "$PKI_DIR/server/keys"
chown -R 10001:10001 "$PKI_DIR/server/keys"
for n in 1 2 3; do cp "$PKI_DIR/server/keys/blocktoken.pub" "$PKI_DIR/dn-$n/"; chown 10001:10001 "$PKI_DIR/dn-$n/blocktoken.pub"; done
mkdir -p "$PKI_DIR/client"; cp "$PKI_DIR/ca/ca.crt" "$PKI_DIR/client/ca.crt"; chmod 644 "$PKI_DIR/client/ca.crt"
sed -e "s/__MDS_PASS__/$RABBIT_MDS_PASS/" -e "s/__DN1_PASS__/$RABBIT_DN1_PASS/" -e "s/__DN2_PASS__/$RABBIT_DN2_PASS/" -e "s/__DN3_PASS__/$RABBIT_DN3_PASS/" \
  "$HERE/../rabbitmq/definitions.template.json" > "$PKI_DIR/rabbitmq/definitions.json"
chown 100:101 "$PKI_DIR/rabbitmq/definitions.json"; chmod 600 "$PKI_DIR/rabbitmq/definitions.json"
```

Los UID importan: `10001` es el usuario de las imágenes de servidor y nodos, y `100:101` es `rabbitmq` en la imagen Alpine. Si no coinciden, el proceso no puede leer su `tls.key`.

### Emisión en producción

La clave privada de cada máquina se genera allí y nunca viaja. Solo se mueven el CSR y el certificado.

```bash
# VM del servidor, una vez
export PKI_DIR=/etc/dfsha/pki
sudo -E server/pki/init-ca.sh
sudo -E server/pki/gen-token-keys.sh /etc/dfsha/server/keys
sudo -E server/pki/gen-csr.sh server /etc/dfsha/server
sudo -E server/pki/sign-csr.sh /etc/dfsha/server/server.csr /etc/dfsha/server/tls.crt "IP:10.0.1.10"
sudo -E server/pki/gen-csr.sh rabbitmq /etc/dfsha/rabbitmq
sudo -E server/pki/sign-csr.sh /etc/dfsha/rabbitmq/rabbitmq.csr /etc/dfsha/rabbitmq/tls.crt "IP:10.0.1.10"

# VM de cada nodo: genera la clave y el CSR, y copia el CSR a la VM del servidor
sudo sh gen-csr.sh dn-1 /etc/dfsha/tls

# VM del servidor: firma con la IP privada del nodo
sudo -E server/pki/sign-csr.sh dn-1.csr dn-1.crt "IP:10.0.1.21"
```

Devuelve a cada nodo su `tls.crt`, `ca.crt` y `blocktoken.pub`. Al cliente solo `ca.crt`. Copia también `ca.crt` a los directorios del servidor y de RabbitMQ, y ajusta el dueño de las claves: UID `10001` para servidor y nodos, `100:101` para RabbitMQ. Si cambia la IP de una máquina, hay que reemitir su certificado. `ca.key` se queda en el host y nunca se monta en un contenedor.

## 5. RabbitMQ

`server/rabbitmq/rabbitmq.conf`:

```ini
listeners.tcp = none
listeners.ssl.default = 5671
ssl_options.cacertfile = /secrets/ca.crt
ssl_options.certfile   = /secrets/tls.crt
ssl_options.keyfile    = /secrets/tls.key
ssl_options.verify     = verify_none
ssl_options.fail_if_no_peer_cert = false
ssl_options.versions.1 = tlsv1.3
definitions.import_backend = local_filesystem
definitions.local.path = /secrets/definitions.json
```

`verify_none` significa que RabbitMQ no pide certificado de cliente, que es la decisión del equipo. Los clientes sí validan al broker.

`server/rabbitmq/definitions.template.json` define toda la topología. Cargar definiciones también evita que exista el usuario `guest`.

```json
{
  "users": [
    {"name":"mds","password":"__MDS_PASS__","tags":[]},
    {"name":"dn-1","password":"__DN1_PASS__","tags":[]},
    {"name":"dn-2","password":"__DN2_PASS__","tags":[]},
    {"name":"dn-3","password":"__DN3_PASS__","tags":[]}
  ],
  "vhosts": [{"name":"dfsha"}],
  "permissions": [
    {"user":"mds","vhost":"dfsha","configure":"^$","write":"^dfs\\.commands$","read":"^mds\\..*$"},
    {"user":"dn-1","vhost":"dfsha","configure":"^$","write":"^dfs\\.events$","read":"^dn\\.dn-1\\.commands$"},
    {"user":"dn-2","vhost":"dfsha","configure":"^$","write":"^dfs\\.events$","read":"^dn\\.dn-2\\.commands$"},
    {"user":"dn-3","vhost":"dfsha","configure":"^$","write":"^dfs\\.events$","read":"^dn\\.dn-3\\.commands$"}
  ],
  "exchanges": [
    {"name":"dfs.events","vhost":"dfsha","type":"topic","durable":true,"auto_delete":false,"internal":false,"arguments":{}},
    {"name":"dfs.commands","vhost":"dfsha","type":"direct","durable":true,"auto_delete":false,"internal":false,"arguments":{}}
  ],
  "queues": [
    {"name":"mds.heartbeats","vhost":"dfsha","durable":true,"auto_delete":false,"arguments":{"x-message-ttl":5000,"x-max-length":1000,"x-overflow":"drop-head"}},
    {"name":"mds.events","vhost":"dfsha","durable":true,"auto_delete":false,"arguments":{}},
    {"name":"dn.dn-1.commands","vhost":"dfsha","durable":true,"auto_delete":false,"arguments":{}},
    {"name":"dn.dn-2.commands","vhost":"dfsha","durable":true,"auto_delete":false,"arguments":{}},
    {"name":"dn.dn-3.commands","vhost":"dfsha","durable":true,"auto_delete":false,"arguments":{}}
  ],
  "bindings": [
    {"source":"dfs.events","vhost":"dfsha","destination":"mds.heartbeats","destination_type":"queue","routing_key":"node.heartbeat.*","arguments":{}},
    {"source":"dfs.events","vhost":"dfsha","destination":"mds.events","destination_type":"queue","routing_key":"node.register.*","arguments":{}},
    {"source":"dfs.events","vhost":"dfsha","destination":"mds.events","destination_type":"queue","routing_key":"node.report.*","arguments":{}},
    {"source":"dfs.events","vhost":"dfsha","destination":"mds.events","destination_type":"queue","routing_key":"block.*.*","arguments":{}},
    {"source":"dfs.commands","vhost":"dfsha","destination":"dn.dn-1.commands","destination_type":"queue","routing_key":"dn-1","arguments":{}},
    {"source":"dfs.commands","vhost":"dfsha","destination":"dn.dn-2.commands","destination_type":"queue","routing_key":"dn-2","arguments":{}},
    {"source":"dfs.commands","vhost":"dfsha","destination":"dn.dn-3.commands","destination_type":"queue","routing_key":"dn-3","arguments":{}}
  ]
}
```

El TTL de 5 s en `mds.heartbeats` descarta latidos viejos, para que un latido atrasado no haga parecer vivo a un nodo caído.

No declares `Queue`, `Exchange` ni `Binding` como beans: el usuario `mds` no tiene permiso `configure`. Conversor de mensajes:

```java
@Bean
MessageConverter conversorMensajes() {
    var converter = new JacksonJsonMessageConverter();
    converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
    return converter;
}
```

`INFERRED` hace que Spring use el tipo del parámetro del listener y no la clase Java del nodo.

## 6. Autenticación y autorización

| Clase | Responsabilidad |
|---|---|
| `AuthController` | `/v1/auth/*` |
| `AuthService` | Login, refresh, logout |
| `PasswordHasher` | Interfaz sobre `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`. Contraseñas de ≥ 12 caracteres |
| `AccessTokenService` | JWT EdDSA de 15 min con JJWT: `Jwts.builder()...signWith(key, Jwts.SIG.EdDSA)` |
| `RefreshTokenService` | 32 bytes de `SecureRandom`, 8 h, revocable. Guarda solo su SHA-256 y rota en cada uso |
| `LoginThrottle` | Ventana deslizante en memoria: `429` tras 5 fallos por cuenta o 10 por IP en 15 min |
| `JwtAuthenticationFilter` | `OncePerRequestFilter`; pone el `Authentication` en el contexto |
| `CurrentUser` | Acceso tipado al usuario actual |

No uses `oauth2-resource-server`: su decodificador Nimbus no valida EdDSA sin dependencias adicionales. Configura `SecurityFilterChain` así:

```java
return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
                .requestMatchers("/v1/auth/login", "/v1/auth/refresh", "/actuator/health").permitAll()
                .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(e -> e
                .authenticationEntryPoint(problemEntryPoint)
                .accessDeniedHandler(problemDeniedHandler))
        .build();
```

CSRF se desactiva porque no hay cookies: el token viaja en `Authorization`.

- **Login:** si el usuario no existe, verifica igual contra un hash fijo, para que el tiempo de respuesta no revele qué usuarios existen. Responde siempre `INVALID_CREDENTIALS`.
- **Administrador inicial:** si no existe, créalo al arrancar con `DFS_ADMIN_PASSWORD`.
- **Claves:** `${dfs.tls-dir}/keys/jwt.key`, `jwt.pub` y `blocktoken.key`, leídas al arrancar por un `PemKeyLoader`. Sin clave, el servidor no arranca.

`openssl genpkey` produce PKCS#8:

```java
String base64 = Files.readString(path).replaceAll("-----[A-Z ]+-----|\\s", "");
PrivateKey key = KeyFactory.getInstance("Ed25519")
        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
```

## 7. Implementación por módulo

### Modelo de estado

Todo cambio de estado durable es una operación del WAL. No hay base de datos.

```java
public sealed interface JournalOp permits CreateDir, DeleteDir, Move, CopyFile, DeleteFile,
        OpenHandle, CloseHandle, CommitVersion, AcquireLock, ReleaseLock,
        CreateUser, UpdateQuota, RevokeRefreshToken, AuditEntry { }

public interface EditLog {
    long anexar(JournalOp op);          // Devuelve el LSN tras fsync. Lanza si no es durable.
    void reproducir(long afterLsn, Consumer<LogEntry> consumer);
}

public interface MetadataStore {
    <T> T leer(Function<MetadataState, T> query);
    <T> T escribir(Function<MetadataState, Mutation<T>> decide);
}
```

Cada operación es un `record` en `journal/op/` y debe ser **determinista**: contiene el tiempo, los IDs y los valores ya resueltos. Aplicarla no consulta el reloj ni genera aleatorios, para que la recuperación reconstruya exactamente el mismo estado.

`MetadataStore.escribir`, bajo el write lock de un `ReentrantReadWriteLock`:

1. `decide` valida contra el estado y produce la operación y el resultado. No modifica nada.
2. `editLog.anexar(op)` escribe y hace `fsync`.
3. `state.aplicar(op)` aplica en memoria.
4. Devuelve el resultado.

Si el paso 2 falla, el estado no cambió y se responde error. El orden inverso rompe la garantía G1.

`MetadataState` contiene solo mapas y lógica pura, sin Spring, y es lo que se serializa en el snapshot:

| Mapa | Clave y valor |
|---|---|
| `directories` | `(parentInode, nombre)` a `inodeId` |
| `inodes` | `inodeId` a `{owner, mode, size, version, fileSha256, ctime, mtime, blocks[]}` |
| `blocks` | `blockId` a `{gen, len, sha256, refCount}` |
| `pendingBlocks` | Bloques asignados sin commit, con hora de creación, para GC a 1 h |
| `handles`, `locks` | Handles y locks abiertos |
| `users`, `refreshTokens` | Hash Argon2id, rol, cuota, uso; SHA-256 de refresh tokens |
| `fencingSequence` | Contador monótono |

**No van al WAL:** las réplicas de cada bloque y el registro de nodos (estado blando en `ClusterState`, reconstruido con block reports) ni las renovaciones de lease, que generarían una escritura por cliente cada 10 s.

### `journal`

Archivos en `${dfs.data-dir}/journal/`:

- `edits-<primerLSN>.log`: una línea JSON por entrada, `{"lsn":123,"op":{...},"crc":2894512}`, con CRC32C del JSON de `op`.
- `snapshot-<lsn>.json`: estado completo.

`FileEditLog.anexar` serializa, escribe y llama a `FileChannel.force(false)`.

`JournalRecovery.recuperar()`:

1. Toma el snapshot válido de mayor LSN. Si está dañado, usa el anterior.
2. Lee los logs en orden y aplica las entradas con `lsn > snapshot`.
3. Una última línea incompleta o con CRC inválido es una escritura interrumpida: trunca el archivo ahí. **En cualquier otra posición es corrupción: detén el arranque con un error claro.**
4. Comprueba que los LSN sean consecutivos.

`SnapshotService` corre cada 10 min o 100 000 entradas. Serializa bajo read lock a `.tmp`, hace `fsync`, renombra con `ATOMIC_MOVE`, hace `fsync` del directorio, rota el log y conserva los 2 últimos snapshots.

Usa `@JsonTypeInfo(use = Id.NAME, property = "t")` y `@JsonSubTypes` en `JournalOp`, con nombres estables: renombrar un `record` no debe romper un log existente.

### `namespace`

- `PathNormalizer`: canoniza, rechaza `..` que escape, nombres vacíos y caracteres de control.
- `AccessPolicy.verificar(user, path, operation)`: `owner == sub` o `ADMIN`, y dentro de `/home/<sub>`. Una sola implementación que usan todos los casos de uso.
- `mv` es una sola operación de WAL. Valida que el destino no esté dentro del origen.
- `cp` crea un inodo con los mismos bloques e incrementa `refCount`, sin copiar datos.
- `rm` quita la entrada y decrementa `refCount` en una sola operación. Responde 204.
- Crea `/home/<usuario>` al crear un usuario.
- `ls` pagina con cursor sobre nombres ordenados.

### `cluster`: nodos y fallas

`ClusterState` es concurrente y en memoria: `nodeId` a `{host, clientPort, replicationPort, az, capacity, used, connections, ultimoContacto, status, publicKey, reported}`, más `blockId` a `Set<Replica>`. `status` es `ALIVE`, `SUSPECT` o `DEAD`.

```java
@RabbitListener(queues = "mds.heartbeats")
void alRecibirLatido(HeartbeatMessage body, Message message) { ... }
```

`mds.events` recibe varios tipos: usa un listener con `@RabbitHandler` por tipo o despacha con `getReceivedRoutingKey()`.

`MessageAuthenticator.verificar(message, nodeIdDelCuerpo)` exige que `getReceivedUserId()`, el último segmento de la routing key y `nodeId` coincidan. Si no, registra y descarta. Sin esto un nodo podría reportar en nombre de otro: el broker solo garantiza el `user_id`.

| Mensaje | Acción |
|---|---|
| Heartbeat de nodo conocido | Actualiza `ultimoContacto` con `Clock` (hora de recepción) y métricas. `SUSPECT` o `DEAD` vuelve a `ALIVE` |
| Heartbeat de nodo desconocido | Envía `RESYNC`, como máximo uno cada 30 s por nodo |
| Registro | Valida el certificado: `cert.verify(ca.getPublicKey())`, `checkValidity()` y `CN == nodeId`. Guarda su clave pública |
| Block report | Reemplaza las réplicas de ese nodo. Bloque desconocido: `DELETE_BLOCK` tras el periodo de gracia. Marca `reported = true` |
| `block.received` | Agrega la réplica |
| `block.deleted` | Quita la réplica |
| `block.corrupt` | Quita la réplica y programa reparación |

`FailureDetector.revisar()`, cada segundo:

```java
if (!brokerMonitor.estaConectado()) {
    return;
}
for (Node node : cluster.nodos()) {
    Duration silence = Duration.between(node.ultimoContacto(), clock.instant());
    // ALIVE -> SUSPECT a 10 s; SUSPECT -> DEAD a 30 s.
}
```

**El punto más delicado:** como los heartbeats pasan por RabbitMQ, una caída del broker parece la caída de todos los nodos y dispararía una tormenta de re-replicación. `BrokerMonitor` implementa `ConnectionListener` de Spring AMQP, se registra en la `CachingConnectionFactory` y, en `onCreate` tras una desconexión, llama a `cluster.reiniciarUltimoContacto(clock.instant())`.

Los mensajes malformados lanzan `AmqpRejectAndDontRequeueException` para no reencolarse sin fin.

`CommandGateway.enviar(nodeId, command)` usa `rabbitTemplate.convertAndSend("dfs.commands", nodeId, command)`. Los comandos son `record` de una interfaz `sealed` con `@JsonTypeInfo` en la propiedad `type`.

**Tras reiniciar:** `SafeMode` arranca activo y se apaga cuando al menos 2 nodos tienen `reported = true`. Un interceptor responde `503 SAFE_MODE` en `/v1/files` y `/v1/locks`. Mientras esté activo, `RepairService` y `GarbageCollector` no actúan: una réplica aún no reportada no está perdida. Los locks y handles del WAL reciben un TTL completo para que sus dueños los renueven.

### `blocks`

```java
public interface ReplicaPlacementPolicy {
    List<Node> elegir(List<Node> candidates, int replicas, long blockSize, Set<String> excluded);
}
```

`PowerOfTwoChoicesPolicy`:

1. Candidatos: `ALIVE`, espacio libre > 2B y no excluidos.
2. Réplica 1: toma dos al azar y queda el mejor por conexiones activas y % de disco.
3. Réplica 2: el mejor candidato restante en **otra AZ**.
4. Sin dos nodos en AZ distintas: `INSUFFICIENT_NODES`.

Rota el nodo inicial entre bloques consecutivos de un archivo, para repartirlos entre los tres nodos. Inyecta `RandomGenerator` para tener tests deterministas.

`BlockTokenIssuer`: `aud=dfsha-datanode`, `ops`, `blk` con `"<blockId>:<gen>"`, 10 min, firmado con `blocktoken.key`. Es distinta de `jwt.key` para que un access token nunca sirva como block token.

`BlockIdGenerator`: `blk_` + UUIDv7.

### `locks`

```java
public interface LockManager {
    Lock adquirir(LockRequest request) throws LockConflictException;
    void renovar(String lockId, String owner);
    void liberar(String lockId, String owner);
    boolean cubre(String fencingToken, long inodeId, BlockRange range);
}
```

- Modos `SHARED` y `EXCLUSIVE`, sobre todo el archivo o un rango de índices. Dos rangos entran en conflicto si se solapan y alguno es `EXCLUSIVE`.
- Las lecturas no se bloquean.
- TTL de 30 s. Un `LockReaper` libera los vencidos y despierta a la cola.
- Con `waitMs`, la solicitud espera en cola FIFO por archivo, mediante `CompletableFuture`. Las nuevas no se adelantan a las que esperan. Los virtual threads permiten bloquear sin agotar hilos.
- Cada concesión incrementa `fencingSequence` y se registra en el WAL.

### `files`

Los nombres de esta sección son los de las rutas REST. Los métodos de los casos de uso se llaman `abrir`, `asignar`, `recuperarBloque`, `confirmar`, `mantenerActivo` y `cerrar`.

- **`open`:** `w` y `a` toman lock exclusivo implícito de todo el archivo. `exclusive=true` con ruta existente da 409. `r` guarda la versión para que el GC no borre sus bloques y devuelve bloques, réplicas vivas ordenadas y `readToken`. `a` y `rw` también devuelven bloques y `readToken`, porque el cliente lee para hacer copy-on-write.
- **`allocate`:** valida handle, cuota y lock. Crea IDs con `gen = 1`, registra `pendingBlocks` y emite `writeToken`.
- **`recover-block`:** misma posición, mismo `blockId`, `gen + 1`, con `failedNodes` excluidos.
- **`HandleReaper`:** si un handle no recibe `keepalive` en 30 s, aborta la sesión y libera sus locks.

`commit`, en este orden exacto:

```
1. El handle existe y no venció                       -> 410 HANDLE_EXPIRED
2. Commit repetido del mismo handle y contenido       -> devuelve la versión ya creada
3. fencingToken (lease en w y a, lock en rw) es el vigente
   y cubre los índices                                -> 409 STALE_FENCING_TOKEN
4. CAS: cada índice tocado no cambió desde baseVersion -> 409 VERSION_CONFLICT
5. Mapa final = base + bloques del commit
6. Los índices 0..n-1 existen sin huecos              -> 422 BLOCK_NOT_DURABLE
7. Cada bloque nuevo tiene 2 recibos válidos, de nodos distintos,
   de la generación vigente                           -> 422 BLOCK_NOT_DURABLE
8. En w y a, la suma de longitudes es igual a size    -> 422 BLOCK_NOT_DURABLE
9. En w y a, fileSha256 coincide con el hash de hashes -> 422 BLOCK_NOT_DURABLE
   En rw el cliente manda fileSha256 nulo: el servidor calcula size y hash
   del mapa final, porque otro escritor pudo cambiar otros rangos
10. La cuota alcanza                                  -> 507 QUOTA_EXCEEDED
11. Una sola entrada CommitVersion en el WAL: nueva versión, refCount
    de bloques nuevos +1, reemplazados -1, pendingBlocks limpios.
    La baseVersion del handle avanza: un handle publica una vez por flush
12. Responde {version}
```

`ReceiptVerifier`:

```java
String payload = "dfsha-receipt-v1|" + blockId + "|" + gen + "|" + length + "|" + sha256 + "|" + nodeId;
Signature verifier = Signature.getInstance("SHA256withECDSA");
verifier.initVerify(nodePublicKey);
verifier.update(payload.getBytes(StandardCharsets.UTF_8));
boolean valid = verifier.verify(signature);
```

Las rutas con dos puntos funcionan en Spring MVC: `@PostMapping("/files:open")`, `@PostMapping("/files/{h}:allocate")`.

### `cluster`: reparación y GC

`RepairService`, cada 30 s y solo fuera de `SafeMode`:

- Bloque referenciado con menos de 2 réplicas vivas, si su nodo lleva 2 min `DEAD` o la réplica se marcó corrupta: elige fuente y destino en otra AZ, y envía `REPLICATE_BLOCK` a la fuente con un token `ops=w`.
- Registra el trabajo en curso con un tiempo límite, para no repetir comandos en cada ciclo.
- Bloque con más de 2 réplicas, por un nodo que volvió: `DELETE_BLOCK` a la sobrante.
- Bloque sin ninguna réplica viva: registra un error. No inventes una recuperación.

`GarbageCollector`, cada minuto: bloques con `refCount = 0` tras 10 min y sin lectores, y `pendingBlocks` con más de 1 h. Envía `DELETE_BLOCK` a cada réplica y olvida el bloque al recibir todos los `block.deleted`.

### `audit` y errores

Un `AuditEntry` por operación que muta estado y por cada intento de login: quién, qué, cuándo, IP, resultado, `traceId`. Mantén los bytes por usuario para cuotas y accounting. Expón en `/actuator/prometheus` contadores de operaciones, latencia por endpoint, nodos por estado y bloques con menos de 2 réplicas.

Errores HTTP: una única jerarquía `DfsException(HttpStatus, code, detail)` y un `@RestControllerAdvice` que produce `ProblemDetail` con `code` y `traceId`. Usa los códigos exactos del README raíz: el cliente depende de ellos.

## 8. Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/server
COPY server/ ./
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --user-group dfs && mkdir -p /data && chown dfs:dfs /data
COPY --from=build /src/server/build/libs/*-SNAPSHOT.jar /app/app.jar
USER dfs
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

## 9. Pruebas y lista de entrega

Tests obligatorios. Usa `Clock` de prueba en lugar de `sleep`, y `@TempDir` para el WAL.

| Área | Casos |
|---|---|
| WAL | Reaplicar produce un estado idéntico; última línea truncada; CRC inválido en medio detiene el arranque; snapshot + log |
| Commit | Cada rama del algoritmo; dos commits concurrentes; commit repetido; recibo falso; recibo de otra generación |
| Locks | Matriz de conflictos, rangos disjuntos, FIFO, vencimiento, fencing monótono tras reiniciar |
| Ubicación | AZ distintas siempre; excluidos; candidatos insuficientes; reparto entre nodos |
| Detector | `SUSPECT`, `DEAD`, retorno a `ALIVE`, **congelado con broker caído**, reinicio de `ultimoContacto` |
| Mensajes | `user_id` inconsistente descartado; certificado de otra CA rechazado; `CN` distinto rechazado |
| Seguridad | 401, 403, 429; `..` en rutas; acceso a otro `/home`; refresh revocado o reutilizado |
| GC | Periodo de gracia, lectores activos, huérfanos, `refCount` con `cp` |

Comprobación sobre el sistema en marcha:

- [ ] `docker compose up` deja servidor y broker sanos, y `curl --tls-max 1.2` falla.
- [ ] Un reinicio con `kill -9` no pierde ningún commit confirmado.
- [ ] Detener un nodo: `SUSPECT`, `DEAD` y re-replicación.
- [ ] Detener RabbitMQ un minuto: ningún nodo pasa a `DEAD`.
- [ ] `rm` borra los bloques físicos tras el periodo de gracia.
