# Recepción de solicitudes de teléfono

Issue #16. Milestone: Disponibilidad y reservas.

## Contrato

Consumir `AdditionalInformationRequested`, schemaVersion entero 1, desde
`alumnos.turnos.acciones.proyecto-pilar-machinea`. La clave Kafka debe coincidir con
reservationProcessId. Validar eventId, holdId, message, requiredField PHONE_NUMBER,
occurredAt y expiresAt posterior a occurredAt. El contrato no incluye groupId en
este evento: la separación de grupos se realiza mediante el topic configurado.
Los identificadores admiten hasta 100 caracteres, el mensaje 2000 y el JSON 64 KiB
medidos como caracteres Java. No se exige que el evento todavía esté vigente al
recibirlo: puede ser una entrega atrasada.

## Persistencia y orden

Flyway V4 agrega booking_event_inbox y phone_request_event_id al proceso; no modifica
migraciones anteriores. La recepción es una transacción MySQL con aislamiento
READ_COMMITTED: insertar evento, comprobar duplicado, bloquear proceso, aplicar
solicitud y guardar resultado antes de confirmar el offset Kafka. Este aislamiento
permite leer la solicitud precedente incluso si otro consumidor la confirmó mientras
se esperaba el bloqueo del proceso.

- Mismo eventId y mismo JSON semántico: no repetir el efecto.
- Mismo eventId y contenido diferente: rechazar y revertir la transacción.
- Proceso conocido y hold distinto: rechazar, sin avanzar el offset.
- CONFIRMING, CONFIRMATION_UNCERTAIN o WAITING_FOR_PHONE: guardar requestEventId,
  vencimiento y WAITING_FOR_PHONE; limpiar el error anterior.
- Una respuesta REST tardía no cambia ese resultado porque solo actualiza CONFIRMING.
- Solicitudes anteriores o del mismo occurredAt que la ya aplicada quedan IGNORED.
- Estados finales no retroceden; la solicitud queda IGNORED.
- Proceso desconocido o aún en creación/HELD: conservar PENDING, sin inventar un
  propietario ni repetir peticiones a cátedra. Cada 30 segundos se revisan hasta 100
  eventos cuyo proceso ya es correlacionable y salió de esos estados.
- Otros tipos con envoltura válida se conservan completos como DEFERRED. Sus efectos
  se implementarán en el bloque de resultados finales; no se descartan al confirmar
  el offset. Esa implementación deberá reprocesar las filas DEFERRED.

Un proceso cuyo hold remoto nunca pudo recuperarse permanece pendiente hasta que
exista una correlación segura. No hay un reintento remoto automático.

## API propia

`GET /api/reservation-processes/{id-local}/phone-request`, con JWT propio y cuenta activa:

- 200: requestEventId, requiredField, message, expiresAt.
- 204: proceso propio sin solicitud aplicada todavía.
- 404: proceso inexistente o de otro usuario.
- 401: sin autenticación válida o cuenta desactivada.
- 400: identificador local inválido.

Todas las respuestas llevan Cache-Control no-store. La respuesta es la última
solicitud aplicada, incluso si luego venció o el proceso cambió de estado. Debe
consultarse también el estado del proceso; esta consulta no autoriza enviar un
telefono ni representa una reserva confirmada.

## Configuración y operación

En `.env` local, mantener BOOKING_KAFKA_ENABLED=false cuando no se use la integración.
Para activarla, con ZeroTier conectado y el topic previamente provisionado:

```dotenv
BOOKING_KAFKA_ENABLED=true
BOOKING_KAFKA_BOOTSTRAP_SERVERS=172.17.201.1:9094
BOOKING_KAFKA_ACTIONS_TOPIC=alumnos.turnos.acciones.proyecto-pilar-machinea
BOOKING_KAFKA_CONSUMER_GROUP=alumnos-proyecto-pilar-machinea
```

Reiniciar/reconstruir booking después del cambio. Compose propaga estas variables.
No se crean topics automáticamente. Conservar el grupo entre reinicios para retomar
los offsets. Las réplicas del mismo servicio deben compartir también MySQL.

Se usa earliest para un grupo sin offset, commit por registro y auto-commit desactivado.
Si falla MySQL, el evento es inválido o el identificador es conflictivo, el consumidor
se detiene sin confirmar ese registro. Health informa DOWN cuando Kafka está activado
pero no hay consumidor con particiones asignadas. Corregir la causa y reiniciar para
volver a recibirlo. No borrar filas ni adelantar offsets para ocultar el problema.

Las pruebas usan MySQL y Kafka temporales en Docker y un servidor HTTP local para
las integraciones REST. No producen reservas ni eventos en el servidor de cátedra.
La verificación real contra ese servidor sigue pendiente.

Este bloque recibe la solicitud. El envío de AdditionalInformationSubmitted se implementa
en la [issue #18](../enviar-telefono/SPEC.md). El procesamiento de los resultados
finales sigue pendiente.
