# Envío de teléfono para completar el proceso de reserva

Issue #18. Milestone: Disponibilidad y reservas.

## API propia

`POST /api/reservation-processes/{id-local}/phone`, con JWT propio y cuenta activa:

```json
{
  "requestEventId": "request-event-recibido",
  "phoneNumber": "+54 (9261) 555-12.34"
}
```

Usar el requestEventId obtenido mediante GET `/api/reservation-processes/{id-local}/phone-request`.
Se quitan espacios, puntos, guiones y paréntesis del teléfono; el resultado debe
cumplir `^\+?[0-9]{7,15}$`. Ambos campos son obligatorios, con máximo de 100 caracteres
antes de normalizar. No se acepta un grupo, hold ni propietario elegidos por el cliente.

La solicitud debe ser la última recibida para un proceso propio WAITING_FOR_PHONE,
con vencimiento futuro. El servidor guarda un evento pendiente y devuelve 202,
Location `/api/reservation-processes/{id-local}/phone-submission` y Cache-Control no-store.
No espera a Kafka para contestar. La respuesta incluye eventId, requestEventId,
deliveryStatus, attempts y publishedAt, sin exponer el teléfono.

Repetir el mismo requestEventId y teléfono normalizado devuelve el envío existente,
incluso si ya venció o cambió el proceso, sin volver a encolarlo. Enviar otro teléfono
para esa misma solicitud produce 409 PHONE_ALREADY_SUBMITTED. Se puede enviar otro
número cuando cátedra emita una solicitud nueva con otro requestEventId.

Otros errores: 409 PHONE_NOT_EXPECTED (estado incompatible), PHONE_REQUEST_CHANGED
(solicitud ausente o distinta), PHONE_REQUEST_EXPIRED (solicitud vencida); 404
PROCESS_NOT_FOUND tanto para proceso ajeno como inexistente; 401 para usuario sin
JWT válido o desactivado; 403 sin ROLE_USER; 400 INVALID_HOLD_REQUEST para formato
inválido, reutilizando el contrato de errores de los endpoints del proceso.

`GET /api/reservation-processes/{id-local}/phone-submission` devuelve 200 con el envío
asociado a la solicitud actual, o 204 si todavía no tiene envío. Aplica la misma
protección de propietario y cuenta activa. Una solicitud nueva no muestra el envío
anterior como si fuera su respuesta. Los errores de persistencia devuelven 503
HOLD_SERVICE_UNAVAILABLE; un fallo al responder al cliente no demuestra que el insert
no se haya confirmado: se puede repetir exactamente el mismo cuerpo.

## Mensaje a cátedra

El servidor construye `AdditionalInformationSubmitted` con schemaVersion 1, eventId
UUID propio, groupId técnico configurado, reservationProcessId y holdId persistidos,
requestEventId, information.phoneNumber normalizado y occurredAt. La clave Kafka es
reservationProcessId y el destino `catedra.turnos.telefono.{groupId}`. Topic, clave,
contenido e identificador se guardan juntos y permanecen iguales en cada reintento.

## Persistencia y entrega

Flyway V5 agrega phone_submission_outbox sin modificar migraciones anteriores.
La creación bloquea el proceso dentro de una transacción READ_COMMITTED y usa una
restricción única por requestEventId. Dos POST simultáneos con el mismo número
comparten un único evento; números distintos no se sobrescriben.

El publicador revisa hasta 20 envíos por ciclo, con dos segundos de pausa entre ciclos.
El scheduler dispone de dos hilos para no bloquear la recuperación del inbox durante
una publicación lenta. Cada envío adquiere
un lease de 60 segundos en una transacción corta y se publica después del commit,
sin mantener bloqueos MySQL durante la comunicación con Kafka. Otros trabajadores
no pueden reclamarlo mientras ese lease esté vigente. Un token de lease evita que
un trabajador antiguo sobrescriba el resultado de un intento más reciente.

| deliveryStatus | Significado |
| --- | --- |
| PENDING | Guardado, esperando publicación o reintento |
| SENDING | Intento reclamado; puede estar en curso o haberse interrumpido |
| PUBLISHED | Kafka confirmó la recepción; todavía no confirma la reserva |
| STOPPED | No se harán más intentos porque cambió la solicitud/estado o venció |

Se usa `acks=all` y productor idempotente. Un fallo o respuesta incierta programa un
reintento después de 30 segundos. Si la aplicación cae, al vencer el lease otro ciclo
puede recuperarlo. Si Kafka recibió el mensaje pero se perdió la respuesta o falló
la actualización de MySQL, se puede publicar otra copia: se conserva el MISMO eventId
para la deduplicación del receptor. No se promete entrega exactamente una vez entre
MySQL y Kafka.

Antes de reclamar se revalidan estado, solicitud actual y vencimiento. STOPPED no
revoca una publicación anterior que pudo ocurrir sin confirmación local. La solicitud
puede vencer o cambiar durante una publicación en curso: cátedra valida el evento.
El proceso sigue WAITING_FOR_PHONE hasta recibir y procesar el resultado final.

La publicación tiene límites de espera (5 segundos para metadatos, 10 segundos de
entrega Kafka y 12 segundos de espera del futuro). No se registran teléfonos ni el
payload del mensaje en los errores del publicador; el listener de errores de Kafka
usa un mensaje fijo.

## Configuración

Se reutiliza BOOKING_KAFKA_ENABLED y BOOKING_KAFKA_BOOTSTRAP_SERVERS. Se agregan:

```dotenv
BOOKING_CATEDRA_GROUP_ID=proyecto-pilar-machinea
BOOKING_KAFKA_PHONE_TOPIC=catedra.turnos.telefono.proyecto-pilar-machinea
```

El topic debe coincidir con el grupo técnico; una combinación inválida impide iniciar
el servicio. El grupo técnico no es el consumer group. Compose propaga ambos valores.
No cambiar la identidad técnica mientras existan envíos pendientes: se conserva en
cada evento el destino original.

Con BOOKING_KAFKA_ENABLED=false el endpoint puede guardar envíos, pero no los publica.
Para entregar, habilitar Kafka y reiniciar con ZeroTier conectado. Si vence el plazo
antes de poder publicarlos, se detienen. No habilitar el publicador esperando que
PUBLISHED equivalga a una reserva confirmada.

## Validación y límites

Pruebas HTTP/MySQL: propiedad, cuenta activa, formato, contrato persistido,
repetición, concurrencia, vencimiento, cambios de solicitud, leases y reintentos.
Pruebas Kafka local: publicación real con clave y contenido del contrato, además
de las pruebas existentes del consumidor. No se envían teléfonos al servidor de
cátedra en las pruebas automatizadas.

Pendiente: prueba real con cátedra y procesamiento de AppointmentConfirmed,
AdditionalInformationRejected, expiración y demás resultados. Los eventos finales
recibidos continúan guardados como DEFERRED por el consumidor de la issue #16.
