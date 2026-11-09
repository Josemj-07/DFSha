# DFSha

Sistema de archivos distribuido, proyecto académico de la UPB. Los archivos se parten en bloques inmutables de hasta 128 MiB, y cada bloque se guarda en 2 de 3 DataNodes ubicados en zonas de disponibilidad distintas. Un servidor de metadatos lleva el espacio de nombres, los permisos, los locks y la ubicación de cada bloque. El cliente le pide al servidor dónde leer o escribir y transfiere los bytes directamente con los nodos.

El sistema sigue funcionando si cae un DataNode: lee de la otra réplica y, a los 2 minutos, el servidor vuelve a replicar los bloques que quedaron con una sola copia. El servidor de metadatos es un punto único de falla: si cae, no hay operaciones hasta que reinicie. Al reiniciar, recupera su estado del WAL y de los snapshots, y los bloques nunca se pierden.

Explicacion tecnica (presentacion en formato de video): https://youtu.be/qYWh5jugSq0?si=toCNMSYi0GyKUES8

| Componente | Tecnología | Comunicación |
|---|---|---|
| Cliente: SDK y CLI `dfs` | Java 21 | HTTPS hacia el servidor, gRPC hacia los nodos |
| Servidor de metadatos y Cluster Manager | Java 21, Spring Boot | API REST `:8443`, AMQP hacia RabbitMQ |
| DataNodes | Java 21, Spring Boot, gRPC | gRPC `:50051` para el cliente, `:50052` entre nodos, AMQP hacia RabbitMQ |
| Broker | RabbitMQ | Heartbeats, registro, block reports, eventos y comandos |

Todos los canales van cifrados con TLS 1.3, con certificados emitidos por una CA propia del proyecto.

## Informe técnico final

El documento final del proyecto es **[docs/informe-tecnico-final.docx](docs/informe-tecnico-final.docx)**. Contiene:

- la arquitectura consensuada;
- qué entra y qué sale del alcance;
- las operaciones y las APIs entre componentes;
- los algoritmos principales;
- la seguridad;
- el despliegue;
- los resultados de las pruebas y las limitaciones.

El diagrama de la arquitectura en AWS está en [docs/arquitectura-aws.png](docs/arquitectura-aws.png).

## Estructura del repositorio

```
DFSha/
├── README.md               Este documento
├── DFSha.pdf               Enunciado y diseño original del proyecto
├── EJECUCION.md            Cómo usar la CLI y provocar fallas en el despliegue de AWS
├── GUION.md                Guion del video de demostración, dividido en 3 partes
├── docker-compose.yml      Sistema completo en una sola máquina para pruebas locales
├── .env.example            Variables de entorno de ejemplo para Docker Compose
├── docs/
│   ├── informe-tecnico-final.docx   Informe técnico final
│   ├── arquitectura-aws.png         Diagrama del despliegue en AWS
│   └── especificacion.md            Contratos compartidos y reglas de construcción
├── proto/dfsha/v1/
│   └── block.proto         Contrato gRPC entre el cliente y los nodos, y entre nodos
├── client/                 SDK y CLI dfs
├── server/                 Servidor de metadatos, CA y configuración de RabbitMQ
└── nodes/                  DataNodes
```

Cada componente es un proyecto Gradle independiente, con su `Dockerfile` y un `README.md` que documenta cómo se construyó. Las imágenes Docker se construyen desde la raíz del repositorio, porque el cliente y los nodos necesitan `proto/`.

### `client/`: SDK y CLI `dfs`

Código en `client/src/main/java/co/edu/upb/dfsha/client/`:

| Paquete | Contenido |
|---|---|
| `cli/` | Comandos de `dfs` y modo interactivo `dfs shell` |
| `sdk/` | API pública para usar el sistema desde Java: `DfsClient`, `DfsFile` y los errores |
| `metadata/` | Llamadas REST al servidor: sesión, archivos, locks y administración |
| `transfer/` | Subida y descarga de archivos: partición en bloques y transferencias en paralelo |
| `data/` | Llamadas gRPC a los nodos, con paso a la otra réplica si una falla o está corrupta |
| `integrity/` | CRC32C, SHA-256 de cada bloque y hash del archivo completo |
| `session/` | Tokens de acceso, su renovación y la sesión guardada en disco |
| `security/` | Confianza en la CA y configuración de TLS |
| `config/` | Configuración leída del entorno y resolución de rutas relativas |

En `client/src/test/` hay un simulador del servidor y de los nodos, para probar el cliente sin el resto del sistema.

### `server/`: servidor de metadatos

Código en `server/src/main/java/co/edu/upb/dfsha/server/`:

| Paquete | Contenido |
|---|---|
| `auth/` | Login, JWT y contraseñas Argon2id |
| `namespace/` | Directorios, permisos y cuotas |
| `files/` | Creación, lectura y versiones de archivos |
| `blocks/` | Asignación de bloques a nodos y block tokens |
| `locks/` | Locks compartidos y exclusivos, con lease |
| `cluster/` | Estado de los nodos, detector de fallas, re-replicación y recolector de basura |
| `journal/` | WAL y snapshots |
| `admin/` | Endpoints de administración: usuarios, cuotas y estado de los nodos |
| `audit/` | Registro de auditoría |
| `common/`, `config/` | Errores comunes y configuración de Spring |

Otros directorios del servidor:

| Directorio | Contenido |
|---|---|
| `pki/` | Scripts de la CA: crear la CA, generar CSR, firmar certificados y generar las claves de los tokens |
| `rabbitmq/` | `rabbitmq.conf` y la plantilla con colas, exchanges, usuarios y permisos |

### `nodes/`: DataNodes

Código en `nodes/src/main/java/co/edu/upb/dfsha/node/`, organizado en puertos y adaptadores:

| Paquete | Contenido |
|---|---|
| `domain/` | Bloques, permisos de escritura y eventos |
| `application/` | Casos de uso: leer, escribir, replicar y borrar bloques, heartbeats, block reports y scrubber |
| `port/` | Interfaces que la aplicación necesita del exterior |
| `adapter/grpc/` | Servicio gRPC para el cliente y cadena de réplica entre nodos |
| `adapter/storage/` | Almacenamiento de bloques en disco y recuperación tras un reinicio |
| `adapter/amqp/` | Mensajería con el servidor, con un outbox para cuando RabbitMQ no está disponible |
| `adapter/crypto/` | Verificación de block tokens y firma de recibos |
| `config/` | Configuración de Spring, gRPC, AMQP y tareas programadas |

## Documentación

| Documento | Para qué sirve |
|---|---|
| [docs/informe-tecnico-final.docx](docs/informe-tecnico-final.docx) | Informe final: arquitectura, APIs, algoritmos, despliegue, resultados y limitaciones |
| [EJECUCION.md](EJECUCION.md) | Comandos de la CLI, pruebas de tolerancia a fallos y alcance, sobre AWS |
| [GUION.md](GUION.md) | Guion del video de demostración |
| [docs/especificacion.md](docs/especificacion.md) | Contratos REST, gRPC y AMQP, seguridad, Docker Compose y reglas de construcción |
| [client/README.md](client/README.md), [server/README.md](server/README.md), [nodes/README.md](nodes/README.md) | Construcción de cada componente |
