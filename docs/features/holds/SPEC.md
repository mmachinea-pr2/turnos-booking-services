# Bloqueos temporales y procesos propios

Issue #12. Rama `12-crear-bloqueos-temporales-y-persistir-procesos`.
Milestone: Disponibilidad y reservas.

## Crear un hold

`POST /api/appointment-holds`, con `Authorization: Bearer <JWT de usuario>` y Content-Type application/json.

```json
{
  "requestId": "c397c7a5-daf6-48b6-8ea4-822bd1022665",
  "professionalId": 15,
  "date": "2026-11-09",
  "startTime": "09:30:00"
}
```

`requestId` es un UUID creado por el cliente para esta intención de reserva. Conservarlo hasta conocer el resultado. Un reintento debe enviar la misma clave y el mismo profesional, fecha y hora. No generar un UUID nuevo automáticamente al perder una respuesta.

Booking obtiene el propietario del `sub` del JWT y comprueba que el usuario siga activo. No acepta identidad enviada por el cliente: `externalPatientId` enviado a cátedra es el UUID del usuario autenticado. Campos extra del JSON no pueden cambiar ese valor.

Antes de un nuevo intento, booking consulta disponibilidad vigente (catálogo y ocupaciones). El slot debe estar completo, habilitado y en el futuro. Se rechazan fracciones de segundo. Que el slot estuviera libre no garantiza que siga libre al crear el hold: se respetan los conflictos informados por cátedra.

Éxito nuevo: 201 y Location `/api/reservation-processes/{id-local}`. Ejemplo:

```json
{
  "id": "c0f84d45-838b-4e08-bd81-cf6f140560e0",
  "requestId": "c397c7a5-daf6-48b6-8ea4-822bd1022665",
  "professionalId": 15,
  "date": "2026-11-09",
  "startTime": "09:30:00",
  "status": "HELD",
  "holdId": "hold-ejemplo",
  "reservationProcessId": "process-ejemplo",
  "expiresAt": "2026-11-07T18:05:00Z",
  "errorCode": null
}
```

Los identificadores remoto y local son distintos. `expiresAt` proviene de cátedra; no se prolonga al consultar o reintentar. El JWT técnico solo sale hacia la URL de cátedra configurada. Se mantiene el contrato de JWT/CSRF existente para los clientes.

## Consultar el proceso

`GET /api/reservation-processes/{id-local}` requiere el mismo usuario propietario. Un identificador inexistente o perteneciente a otro usuario produce 404 PROCESS_NOT_FOUND, sin revelar la existencia de procesos ajenos. JWT ausente/inválido o usuario desactivado: 401; usuario sin ROLE_USER: 403.

Todas las respuestas de estos endpoints llevan Cache-Control: no-store. No se implementa todavía un listado de procesos; el cliente conserva el id local o recupera la representación repitiendo su requestId.

## Persistencia e idempotencia

Migración Flyway V2 crea `reservation_process` con FK al usuario y unicidad de `(user_id, request_id)`, holdId y reservationProcessId remoto. V1 no se modifica.

1. Buscar una petición previa del mismo usuario; si existe, devolver su proceso sin llamar nuevamente a cátedra. Mismo UUID con distinto contenido: 409 REQUEST_ID_REUSED.
2. Para un intento nuevo válido, bloquear brevemente la fila del usuario en MySQL. Esto coordina también instancias distintas del backend.
3. Releer la petición dentro de la transacción; comprobar que no haya otro proceso pendiente del usuario para ese slot; insertar CREATING y confirmar la transacción.
4. Ejecutar el POST remoto fuera de la transacción y sin reintentos ni redirecciones automáticos.
5. Guardar el resultado, actualizando únicamente el estado CREATING.

Otro requestId para el mismo usuario y slot se rechaza con 409 PROCESS_ALREADY_PENDING mientras exista CREATING, UNCERTAIN o HELD vigente. Los conflictos entre usuarios se resuelven de forma autoritativa en cátedra. Los datos de este proceso son históricos; no constituyen una réplica del catálogo.

## Estados y errores

| Estado | Significado | HTTP del POST |
| --- | --- | --- |
| CREATING | Intento persistido; todavía no hay resultado local | 202 al repetir |
| HELD | Respuesta 201 válida con identificadores y vencimiento | 201 nuevo / 200 repetido |
| REJECTED | Rechazo definitivo reconocido | 409 |
| UNCERTAIN | No se puede afirmar si cátedra creó el hold | 202 |
| EXPIRED | El expiresAt conocido ya se alcanzó | 200 |

Al consultar o repetir un HELD vencido se persiste EXPIRED. Un nuevo requestId puede intentar ese slot una vez vencido, sujeto a disponibilidad y cátedra. No se cambian otros estados por reloj.

400 INVALID_HOLD_REQUEST: parámetros/cuerpo inválidos. 409 SLOT_UNAVAILABLE: el horario no aparece disponible en la consulta previa. 503 HOLD_SERVICE_UNAVAILABLE: fallo de disponibilidad o persistencia. Cuerpos de error remotos y credenciales no se devuelven al usuario.

Solo se clasifican como rechazo definitivo los códigos/status documentados: 409 SLOT_ALREADY_HELD/SLOT_ALREADY_RESERVED, 400 PROFESSIONAL_DISABLED/INVALID_SLOT/VALIDATION_ERROR, 404 PROFESSIONAL_NOT_FOUND. Si falta el token técnico, no se envía POST; normalmente lo detecta primero la consulta de ocupaciones.

Timeout, corte de conexión, error 5xx, respuesta inesperada o éxito incompleto se conservan como UNCERTAIN con REMOTE_RESULT_UNKNOWN. Un 202 aquí no significa que el hold exista: significa que el resultado requiere reconciliación.

## Respuesta perdida y recuperación pendiente

Si el proceso termina después del POST remoto y antes de persistir su resultado, puede quedar CREATING. Un reintento devuelve ese proceso y nunca repite el POST. También se conserva el intento si la escritura del resultado falla: el cliente debe repetir el mismo requestId para recuperarlo.

La referencia actual no ofrece consulta de holds por clave de petición ni idempotencia remota para repetir el POST perdido. Por ello no se presupone un TTL máximo ni se desbloquea automáticamente un estado incierto. CREATING sin una ejecución activa y UNCERTAIN requieren reconciliación/soporte; no hay en esta issue un endpoint para forzar su liberación. Se evita crear duplicados a costa de bloquear ese slot para ese usuario mientras no se pueda determinar el resultado.

La confirmación REST se incorpora en la [issue #14](../confirmacion-holds/SPEC.md). Teléfono por Kafka, reserva final y cancelación pertenecen a los próximos bloques. El endpoint de creación de holds crea solamente un bloqueo temporal; no confirma una reserva.

## Validación y uso local

Se reutilizan las URLs, zona y token externo de disponibilidad. No se agregan secretos al repositorio. Reiniciar booking para que Flyway aplique V2 sin borrar la base. Para Docker, reconstruir la imagen cuando corresponda.

En Postman: iniciar sesión con el flujo CSRF, consultar disponibilidad, elegir una fecha/hora disponible, generar un UUID y ejecutar el POST con el JWT de usuario. Guardar Location/id y consultar el proceso. Si el POST devuelve 202 o se pierde la conexión, repetir exactamente el mismo cuerpo. No probar estas operaciones contra cátedra como si fueran consultas de solo lectura: generan holds reales.

Las pruebas automatizadas usan MySQL temporal y un servidor HTTP local; no crean holds en cátedra. Cubren aislamiento por propietario, activación, validación, conflictos, reintentos, expiración, concurrencia, resultado remoto inválido/perdido y fallo de persistencia tras éxito remoto.
