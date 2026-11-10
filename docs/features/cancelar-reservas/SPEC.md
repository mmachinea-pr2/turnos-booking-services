# Cancelación de reservas confirmadas

Issue #22. Milestone: disponibilidad y reservas.

## API propia

POST `/api/reservation-processes/{id-local}/cancel`, con JWT propio y cuenta activa.
El cuerpo es opcional; admite `{ "reason": "Cambio solicitado" }` con un máximo de
500 caracteres. Se usa el proceso local y sus identificadores persistidos, nunca
un identificador remoto elegido por el cliente. No se modifican espacios del motivo.

- 200: cancelación confirmada por REST o Kafka, incluida una repetición ya resuelta.
- 202: REQUESTING o UNCERTAIN; no significa que la reserva se haya cancelado.
- 409 RESERVATION_NOT_CONFIRMED: proceso no confirmado o sin reservationId conocido.
- 409 CANCELLATION_DATA_CHANGED: se intenta cambiar el motivo de un intento pendiente
  o rechazado. Una reserva ya CANCELLED devuelve su estado sin volver a enviarse.
- 409 con cancellation.status REJECTED: rechazo reconocido o falta de credencial técnica.
- 404 PROCESS_NOT_FOUND: proceso inexistente o de otro usuario.
- 401: JWT ausente/inválido o cuenta desactivada; 403 sin ROLE_USER.
- 400 INVALID_HOLD_REQUEST: cuerpo o motivo inválido, reutilizando errores del proceso.

La respuesta incluye processId, reservationProcessId, reservationId, processStatus,
status del intento, reason, startedAt, cancelledAt y errorCode. No confundir
processStatus CONFIRMED con un intento de cancelación REJECTED o UNCERTAIN.
Location apunta a GET `/api/reservation-processes/{id-local}/cancellation`.
Esta consulta devuelve 200 con el intento o 204 si todavía no existe, con la misma
protección de propietario. Todas las respuestas usan Cache-Control no-store.

## Persistencia e integración

Flyway V7 agrega estado, motivo, fecha del intento, fecha de cancelación y error.
También inicializa la cancelación para procesos ya CANCELLED. No modifica V1–V6.

1. Validar la cuenta y bloquear la fila del proceso por id y propietario.
2. Si ya hay un intento, devolverlo sin repetir el POST. Si está cancelado, devolverlo.
3. Solo para CONFIRMED con reserva conocida, guardar REQUESTING y confirmar MySQL.
4. Fuera de la transacción, enviar POST de cátedra
   `/api/appointments/{reservationProcessId}/cancel`, con JWT técnico y motivo.
5. Aceptar únicamente 200 con ambos identificadores coincidentes, estado CANCELLED
   y cancelledAt válido. Guardar CANCELLED en proceso e intento.
6. Actualizar la respuesta únicamente si el intento sigue REQUESTING y el proceso
   sigue CONFIRMED. Kafka puede haber resuelto la operación mientras tanto.

Se reconocen 404 RESERVATION_NOT_FOUND, 403 RESERVATION_OWNER_MISMATCH o
STUDENT_INTEGRATION_NOT_FOUND y 409 RESERVATION_NOT_CANCELLABLE como rechazos.
TECHNICAL_CREDENTIAL_MISSING no realiza ninguna llamada. El rechazo del intento no
cambia la reserva local a CANCELLED ni demuestra su estado remoto actual.

Timeout, desconexión, redirección, 5xx, respuesta inesperada o correlación incorrecta
se guardan como UNCERTAIN. No se siguen redirecciones ni se reintenta HTTP automáticamente.
Si la aplicación cae después de persistir y antes de guardar la respuesta, REQUESTING
permite detectar el intento sin perderlo ni enviarlo nuevamente al repetir la solicitud.
Un error de persistencia puede devolver 503 HOLD_SERVICE_UNAVAILABLE.

Aunque la cancelación remota está documentada como idempotente, este bloque no vuelve
a enviar intentos inciertos ni rechazados. La [reconciliación manual de la issue #24](../consultar-recuperar-reservas/SPEC.md)
permite detectar una cancelación completada consultando cátedra. Reintentar un envío
rechazado, incluso después de corregir una credencial ausente, sigue pendiente.
No borrar el intento manualmente para forzar un reenvío.

## Coordinación con Kafka

AppointmentCancelled actualiza el proceso y el intento de cancelación, limpia errores
y guarda cancelledAt. Puede recibirse sin un intento local previo. Una respuesta REST
posterior no sobrescribe ese resultado. Si REST confirmó primero, el evento Kafka
puede completar la información del resultado; una confirmación atrasada no reabre
la reserva. Siguen aplicándose las validaciones y orden de la issue #20.

GET `/result` representa el último resultado Kafka aplicado; GET `/cancellation`
expone también la confirmación REST. Entre ambos mensajes, el evento histórico de
`/result` puede seguir siendo AppointmentConfirmed aunque el estado actual ya sea CANCELLED.

## Verificación

Pruebas con HTTP local, MySQL y Kafka temporales: identidad, autorización, motivo,
contrato REST, respuestas inciertas, reintentos, concurrencia y llegada anticipada o
tardía de Kafka. No se cancelan reservas reales durante las pruebas automatizadas.
La verificación de integración contra cátedra sigue pendiente.
