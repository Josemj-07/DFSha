`./gradlew build` ejecuta las pruebas unitarias y HTTPS sin necesitar contenedores.

Las dos pruebas con RabbitMQ real se habilitan contra un broker aislado con la topología de `server/rabbitmq/` y contraseña de prueba `server-check-pass-1234` para sus cuatro usuarios:

```sh
DFS_TEST_RABBIT_PORT=15671 DFS_TEST_TLS_DIR=/ruta/pki ./gradlew build
```

El directorio PKI contiene `server/`, `dn-1/` y `dn-2/`, con los certificados y las claves producidos por los scripts documentados. `server/keys/` contiene las claves de tokens. Los certificados deben incluir `localhost` en el SAN.

`DFS_TEST_BROKER_OUTAGE=true` habilita además la parada y el reinicio del contenedor Podman **de prueba** llamado `dfsha-server-check-broker`, durante más de un minuto. No debe apuntar a un broker compartido.

Estas pruebas usan el servidor real, HTTPS y AMQPS. Los nodos son dobles de prueba con archivos temporales; no implementan los DataNodes de producción. Se comprueban recibos, permisos del broker, recuperación tras `kill -9` y borrado por GC tras la gracia, usando `Clock` para avanzar el tiempo del GC.
