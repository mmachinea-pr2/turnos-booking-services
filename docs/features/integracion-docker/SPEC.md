# Integración de catálogo y booking en Docker

Issue #27. Milestone: Seguridad e integración de backends.

## Despliegue conjunto

`compose.integration.yaml` vive en booking y construye ambos repositorios. Por
predeterminado espera `../turnos-catalog-service`; se puede ajustar
CATALOG_REPOSITORY. Catálogo debe incluir la protección JWT de su PR #52.
Booking debe incluir consulta/reconciliación de reservas y cancelación.

Servicios: catalog, booking, catalog-db y booking-db. Cada base tiene usuario,
contraseña, migraciones, red y volumen propios. Las bases no publican puertos.
Los backends comparten la red backend y booking consulta `http://catalog:8080`.
No se usa localhost ni host.docker.internal para comunicar los contenedores.
Ambos reciben la misma BOOKING_JWT_SECRET, emisor y audiencia; catálogo la recibe
como CATALOG_JWT_SECRET. Las contraseñas root se usan solamente al inicializar MySQL.

Puertos de host: catálogo 127.0.0.1:18080, booking 127.0.0.1:18081. Son configurables
con INTEGRATION_CATALOG_PORT e INTEGRATION_BOOKING_PORT. Los Compose individuales
siguen disponibles. El proyecto conjunto tiene volúmenes diferentes de los de
esos proyectos: no migra ni elimina sus datos.

Los healthchecks de las aplicaciones esperan HTTP 200 en /actuator/health.
Booking espera que catálogo esté saludable, y cada aplicación espera su MySQL.
Salud UP no demuestra que catálogo esté inicializado ni que cátedra funcione.

Desde la raíz de booking, si todavía no existe el archivo:

```bash
cp .env.integration.example .env.integration
chmod 600 .env.integration
```

Completar contraseñas y BOOKING_JWT_SECRET (Base64 de al menos 32 bytes). No
sobrescribir el archivo si ya existe. Para generar una clave nueva del entorno
conjunto se puede usar `openssl rand -base64 32`; no publicarla. El archivo está
ignorado por Git y por el contexto de construcción Docker.

```bash
docker compose --env-file .env.integration -f compose.integration.yaml config --quiet
docker compose --env-file .env.integration -f compose.integration.yaml up -d --build --wait
docker compose --env-file .env.integration -f compose.integration.yaml ps
```

## Integración real con cátedra

Completar localmente CATEDRA_JWT y BOOKING_CATEDRA_TOKEN con la credencial técnica,
y las credenciales Redis de la cuenta. Estos tokens no son BOOKING_JWT_SECRET ni el
JWT de un usuario local. ZeroTier debe estar conectado; API 172.17.201.1:8090,
Redis :6379 y Kafka :9094 deben ser accesibles desde Docker, incluidos los brokers
anunciados en sus metadatos.

Para la carga inicial y recuperación periódica habilitar CATEDRA_REST_ENABLED,
CATEDRA_REDIS_ENABLED y CATALOG_PERIODIC_ENABLED. Para eventos de catálogo habilitar
CATEDRA_KAFKA_ENABLED; para reservas, BOOKING_KAFKA_ENABLED. Los topics y el grupo
corresponden a la cuenta técnica; los ejemplos contienen los de este proyecto.
No ejecutar dos instancias locales con el mismo consumer group durante la prueba,
ya que repartirían los mensajes entre ellas.

La secuencia de validación real pendiente es:

1. Salud de ambos servicios y catálogo initialized=true usando JWT de booking.
2. Registro y login con CSRF; consultar categorías, agendas y disponibilidad.
3. Crear hold con requestId único; repetirlo debe conservar el mismo proceso.
4. Confirmar; esperar phone-request, enviar teléfono ficticio y esperar CONFIRMED.
5. Consultar reservas propias; otro usuario no puede leer ni cancelar el proceso.
6. Cancelar la reserva de prueba y verificar CANCELLED.
7. Reiniciar solo las aplicaciones, comprobar datos persistidos y reconciliar.

Usar datos ficticios y cancelar las reservas creadas para validar. No interpretar
una consulta vacía, un timeout o la ausencia de Kafka como autorización para repetir
un POST de creación o confirmación. Conservar el proceso y usar reconciliación.

## Prueba local reproducible, sin cátedra real

`compose.validation.yaml` es una sobreescritura exclusiva para pruebas. Añade un
Kafka local y un simulador REST, fija los endpoints externos a esos contenedores y
no usa credenciales de cátedra. El simulador expone un control de pruebas únicamente
en 127.0.0.1:18090. Nunca desplegar esta sobreescritura para uso real.

Requisitos: Docker Compose v2, Python 3 y Bash, ambos repositorios juntos. Los
puertos 18080, 18081 y 18090 deben estar libres. No ejecutar esta prueba al mismo
tiempo que el despliegue conjunto real, que usa los mismos puertos por defecto.

```bash
bash scripts/validate-integration.sh
```

El script genera `.env.validation` con claves aleatorias solo si no existe,
construye imágenes, crea topics locales, carga un snapshot ficticio por REST y
arranca ambos servicios. Usa el proyecto fijo `turnos-validation` y sus volúmenes.
No consulta los archivos .env de desarrollo. La prueba verifica:

- Login con CSRF y JWT de booking aceptado por el catálogo real.
- Consultas anónimas rechazadas y catálogo inicializado.
- Disponibilidad usando catálogo real y ocupaciones del simulador.
- Creación de hold y repetición idempotente.
- Aislamiento entre dos usuarios ficticios.
- Recepción duplicada de AdditionalInformationRequested por Kafka.
- Envío de teléfono por outbox y lectura del mensaje publicado en el broker.
- Recepción de AppointmentConfirmed, consulta propia y cancelación REST.
- Persistencia de catálogo y proceso después de reiniciar ambas aplicaciones.
- Reenvío de un evento duplicado y reconciliación sin reabrir la reserva cancelada.

El simulador no reproduce toda la semántica de concurrencia, ocupaciones o errores
del servidor real. Su estado remoto vive en memoria; no se reinicia durante la
prueba de persistencia de los backends. La suite Maven cubre los casos de error y
concurrencia específicos; esta prueba verifica el ensamblado de las aplicaciones.
No se ejecuta automáticamente en el CI de un único repo porque necesita catálogo.

Al terminar quedan los contenedores para inspección. Para detenerlos conservando datos:

```bash
docker compose --env-file .env.validation -f compose.integration.yaml -f compose.validation.yaml -p turnos-validation down
```

Solo si se desean descartar **los datos ficticios de esa prueba**, agregar `--volumes`
a ese comando. No eliminar volúmenes de los proyectos individuales ni del despliegue
conjunto real. Conservar .env.validation para reutilizar las contraseñas del volumen.

## Evidencia

Ver [VALIDATION.md](VALIDATION.md). Una prueba local exitosa no reemplaza la
validación autenticada contra cátedra ni garantiza que su broker publique eventos.
