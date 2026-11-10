# Consulta de reservas y reconciliación manual

## Objetivo y acceso

Issue #24, milestone `disponibilidad y reservas`.
Ambas operaciones requieren JWT local válido y cuenta activa. El identificador de
usuario proviene del JWT. Las llamadas a cátedra utilizan la credencial técnica;
no se acepta un paciente elegido por el cliente. No se registran tokens ni cuerpos
remotos con datos personales.

## Consulta

`GET /api/appointments?page=0&size=20`

Responde 200 con `content`, `totalElements`, `page` y `size`, además de
`X-Total-Count` y `Cache-Control: no-store`. Página mínima 0, tamaño entre 1 y 100.
Cada elemento incluye reservationId, reservationProcessId, externalPatientId,
professionalId, appointmentDate, startTime, endTime, status, confirmedAt y cancelledAt.
No se devuelven nombres ni teléfonos de la respuesta externa.

Se consulta `GET /api/appointments` de cátedra con externalPatientId igual al UUID
local. Se verifica el propietario de **cada** resultado: si cátedra devuelve otro
paciente, falla toda la consulta con 503. También se verifican identificadores,
fechas, horarios, estados, duplicados, total y tamaño de página. Se usa el orden
predeterminado del contrato; no se siguen los enlaces Link del servidor. No se
envían filtros de fechas. Los errores externos nunca se convierten en listas vacías.

## Recuperación

`POST /api/reservation-processes/{id-local}/reconcile`

Responde 200 con `outcome` y `process` (estado local actual). Una respuesta 200 no
implica que la reserva esté confirmada; se debe revisar outcome y process.status.

- RECONCILED: se actualizó el proceso con una reserva remota correlacionada.
- UNCHANGED: el estado local ya es equivalente o no admite una regresión.
- NOT_FOUND: no apareció el proceso en la consulta; se mantiene pendiente.
- REMOTE_ID_UNKNOWN: se perdió la identidad remota; no se puede correlacionar.
- CONCURRENT_CHANGE: el proceso cambió mientras se consultaba; prevalece ese cambio.
- SCAN_LIMIT_REACHED: se alcanzó el límite sin encontrarlo; no implica ausencia.

Se recorren como máximo 20 páginas de 100 reservas propias, sin filtrar por estado,
fecha o profesional. La correspondencia exige processId remoto, paciente,
profesional, fecha, hora y, si ya existe, reservationId. No se asocian reservas por
similitud de horario. La consulta remota ocurre fuera de la transacción de escritura;
al actualizar se bloquea la fila y se compara updated_at con el valor anterior.

| Estado remoto | Resultado local |
| --- | --- |
| CONFIRMED | CONFIRMED y reservationId persistido |
| CANCELLED | CANCELLED y resultado de cancelación actualizado |
| PHONE_PENDING | WAITING_FOR_PHONE si estaba CONFIRMING o CONFIRMATION_UNCERTAIN |
| FAILED | INVALID con REMOTE_RESERVATION_FAILED |

CANCELLED nunca retrocede. CONFIRMED solo puede avanzar a CANCELLED. Un proceso ya
WAITING_FOR_PHONE conserva las indicaciones de corrección del teléfono. Los estados
finales detienen envíos pendientes del outbox. La recuperación no fabrica eventos
Kafka ni requestEventId: si falta AdditionalInformationRequested, debe recuperarse
mediante el consumidor/inbox para poder enviar el teléfono.

El resultado de `GET .../result` sigue siendo el último evento Kafka; el proceso
local y `GET .../cancellation` reflejan también la recuperación REST. Un evento
posterior puede completar los datos del resultado sin regresar estados confirmados
o cancelados. No se altera el historial del inbox.

## Límites y errores

- 400: parámetros inválidos; 401: autenticación/cuenta; 404: proceso inexistente o ajeno.
- 503 HOLD_SERVICE_UNAVAILABLE: consulta externa inválida, inaccesible o datos incompatibles.
- No se repite ningún POST de hold, confirmación o cancelación.
- Una reserva CONFIRMED no demuestra que una cancelación incierta haya terminado:
  ese intento permanece incierto hasta observar CANCELLED o revisarlo manualmente.
- Sin remote_process_id (por ejemplo, respuesta perdida al crear el hold), esta API
  no permite recuperar de forma inequívoca el hold. Se conserva UNCERTAIN.
- La paginación externa no ofrece una instantánea consistente. NOT_FOUND y el límite
  de 2000 resultados no autorizan recrear una reserva. Puede repetirse la consulta.
- Esta recuperación es manual, por proceso. No hay barrido automático ni reenvío
  de operaciones rechazadas. Esos comportamientos requieren una política aparte.

## Validación

Pruebas HTTP con servidor externo simulado, pruebas de autorización y persistencia
con MySQL en Docker y pruebas existentes de Kafka. Incluyen consultas cruzadas,
respuestas malformadas, paginación, errores, correlación, estados inciertos y una
actualización Kafka durante la lectura REST. La prueba integral contra la cátedra
queda pendiente; no se han creado ni cancelado reservas reales para esta issue.
