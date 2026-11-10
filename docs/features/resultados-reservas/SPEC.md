# Resultados de reservas por Kafka

Issue #20. Milestone: Disponibilidad y reservas.

## Eventos y estados

| Evento de cátedra | Estado local | Efecto |
| --- | --- | --- |
| AppointmentConfirmed | CONFIRMED | Guarda reservationId y detiene envíos de teléfono pendientes |
| AppointmentCancelled | CANCELLED | Conserva reservationId y motivo opcional |
| AdditionalInformationRejected | WAITING_FOR_PHONE | Guarda el rechazo y permite una corrección hasta expiresAt |
| AppointmentProcessExpired | EXPIRED | Detiene los envíos pendientes |
| AppointmentProcessInvalid | INVALID | Detiene los envíos; requiere reconciliación REST posterior |

El rechazo de un teléfono no equivale al rechazo definitivo de la reserva. INVALID
no prueba que no exista una reserva remota: bloquea otro proceso del mismo usuario
y horario hasta resolverlo. CONFIRMED también bloquea esa creación local.

Se valida schemaVersion, clave Kafka, identificadores y fechas, además del grupo
técnico y el hold persistido. Confirmaciones y cancelaciones deben coincidir con el
profesional, fecha y hora del proceso. Si ya existe reservationId, no puede cambiar.
No se toman datos de reserva de campos adicionales de un evento de rechazo.

## Persistencia y orden

Flyway V6 agrega el resultado al proceso y una ronda de respuesta al outbox. No se
modifican migraciones anteriores. La transacción del inbox bloquea el proceso y
persiste tanto el evento como el estado y la detención de envíos pendientes.

- Duplicados con el mismo eventId y contenido no vuelven a producir efectos.
- Un identificador reutilizado con contenido distinto sigue siendo un error.
- Eventos anteriores al último resultado quedan IGNORED.
- Con la misma fecha de evento, se aplica una precedencia determinista:
  cancelación, confirmación, expiración, invalidez y rechazo de teléfono.
- CONFIRMED solo avanza por Kafka a CANCELLED. Los resultados terminales CANCELLED,
  EXPIRED e INVALID no retroceden ni se reabren automáticamente.
- Una cancelación puede llegar antes que la confirmación: guarda directamente
  CANCELLED y el identificador, e ignora la confirmación atrasada.
- El EXPIRED local calculado por reloj, sin resultado recibido, puede ser reemplazado
  por un resultado válido de la cátedra. No se confunde con una expiración confirmada
  por evento.
- Resultados para una solicitud reemplazada se ignoran. AppointmentProcessInvalid
  admite un requestEventId distinto para representar REQUEST_EVENT_MISMATCH.
- Una respuesta REST tardía no sobrescribe el resultado: la confirmación REST solo
  actualiza procesos que todavía están CONFIRMING.

No se promete resolver contradicciones entre resultados terminales solo con eventos.
La reconciliación REST corresponde a otro bloque.

## Recuperación

Los cinco tipos implementados que estaban DEFERRED se vuelven a validar y aplicar
mediante la recuperación periódica existente. Si el proceso aún no es correlacionable,
el evento permanece PENDING. Un rechazo recibido sin la solicitud de teléfono espera
hasta que esa solicitud exista. Los tipos todavía desconocidos permanecen DEFERRED.

Un evento almacenado inválido no impide intentar recuperar los demás: queda guardado
y se registra un aviso sin payload. Debe revisarse y corregirse la causa. Un evento
nuevo inválido sigue deteniendo el consumidor sin avanzar su offset, como en la
issue #16. No se borran eventos ni se fuerzan offsets para ocultar problemas.

## Consulta del resultado

GET `/api/reservation-processes/{id-local}/result`, con JWT propio y cuenta activa:

- 200: status, reservationId, eventId, eventType, occurredAt, requestEventId, reason,
  message y rejectionEventId; campos no aplicables pueden ser null.
- 204: proceso propio que todavía no tiene un resultado recibido.
- 404: proceso ajeno o inexistente.
- 401: sin JWT válido o cuenta desactivada; 403 sin ROLE_USER.

Se usa Cache-Control no-store. GET del proceso también muestra el estado actualizado.
Repetir la confirmación de un proceso CONFIRMED devuelve 200 sin llamar a cátedra;
CANCELLED, EXPIRED e INVALID devuelven 409, también sin repetir el POST remoto.

## Corregir un teléfono rechazado

El envío inicial mantiene el contrato de la issue #18. Después de recibir un rechazo,
consultar el resultado y enviar al mismo endpoint de teléfono:

```json
{
  "requestEventId": "solicitud-original",
  "phoneNumber": "+5492615551234",
  "rejectionEventId": "identificador-del-rechazo-recibido"
}
```

La corrección genera un nuevo eventId de AdditionalInformationSubmitted, conservando
el requestEventId original. rejectionEventId es un control local y no se agrega al
mensaje de cátedra. Solo se permite corregir el rechazo actual y antes del vencimiento.
Un identificador de rechazo incorrecto produce 409 PHONE_REJECTION_CHANGED.

La unicidad se aplica por solicitud y ronda de respuesta. Repetir la misma corrección
conserva el mismo evento; cambiar su número sin un nuevo rechazo produce 409
PHONE_ALREADY_SUBMITTED. Repetir un envío de una ronda anterior devuelve su resultado
existente y no crea una corrección accidental. GET phone-submission muestra el envío
de la ronda actual, o 204 si todavía no se respondió a ese rechazo.

Los leases de publicaciones anteriores quedan invalidados cuando llega un resultado.
Un trabajador viejo no puede sobrescribir el estado local; una publicación ya en
vuelo no puede revocarse. La cátedra sigue validando el evento recibido.

## Validación

Pruebas con MySQL y Kafka temporales: contratos, propiedad, estados, correlación,
duplicados, orden, cancelación adelantada, recuperación de DEFERRED, corrección de
telefono y protección frente a trabajadores atrasados. La integración real con
cátedra y la reconciliación REST siguen pendientes. El endpoint para solicitar una cancelación se implementa en la
[issue #22](../cancelar-reservas/SPEC.md).
