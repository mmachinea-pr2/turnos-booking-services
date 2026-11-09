# Confirmación inicial de holds

Issue #14. Rama `14-confirmar-holds-y-proceso-reserva`.
Milestone: Disponibilidad y reservas.

## API propia

`POST /api/reservation-processes/{id-local}/confirm`

Se usa el UUID local obtenido al crear el hold, no un holdId elegido por el cliente. Requiere JWT de usuario válido, ROLE_USER y una cuenta activa. Cuerpo JSON:

```json
{
  "patientFirstName": "Ana",
  "patientLastName": "Perez"
}
```

Ambos campos son obligatorios y admiten 2 a 100 caracteres después de quitar espacios externos. El usuario puede completar aquí los nombres aunque su perfil no los tenga. Se conserva exactamente la identidad local del propietario como externalPatientId; campos adicionales enviados para elegir otra identidad no se utilizan.

Un usuario no propietario y un id inexistente reciben el mismo 404 PROCESS_NOT_FOUND. Token ausente/inválido o cuenta desactivada: 401. Sin ROLE_USER: 403. Cuerpo o id inválido: 400 INVALID_HOLD_REQUEST.

La respuesta mantiene la representación de proceso existente y Location `/api/reservation-processes/{id-local}`, con Cache-Control no-store. HTTP 202 con WAITING_FOR_PHONE indica que cátedra aceptó el inicio del paso de teléfono; **no significa reserva confirmada**.

## Flujo y persistencia

Flyway V3 agrega nombres del paciente y confirmation_started_at a reservation_process, sin modificar V1/V2 ni borrar procesos.

1. Validar cuenta y buscar el proceso por id y propietario.
2. Bloquear su fila con SELECT FOR UPDATE. Si sigue HELD pero expiresAt ya se alcanzó, guardar EXPIRED y no llamar a cátedra.
3. Si ya se intentó confirmar, comparar los nombres persistidos. Diferentes datos producen 409 CONFIRMATION_DATA_CHANGED. Los mismos datos devuelven el estado sin repetir el POST.
4. Para un HELD vigente, persistir nombres, instante del intento y CONFIRMING. Confirmar la transacción antes de realizar HTTP.
5. Enviar POST /api/appointment-holds/{holdId}/confirm a cátedra, con JWT técnico, externalPatientId del usuario y nombres validados.
6. Aceptar únicamente HTTP 202, status WAITING_FOR_PHONE, el mismo reservationProcessId persistido y un expiresAt válido. Conservar el nuevo vencimiento.
7. Actualizar únicamente si el estado local todavía es CONFIRMING. Un resultado tardío no reemplaza un estado que otra operación ya haya resuelto.

No se mantiene la transacción ni el bloqueo durante la llamada HTTP. Dos confirmaciones simultáneas comparten el mismo intento; solo una llama a cátedra. No hay reintentos automáticos ni redirecciones HTTP.

## Estados

| Estado | Comportamiento al confirmar/repetir |
| --- | --- |
| HELD vigente | Persiste CONFIRMING e inicia una única llamada remota |
| CONFIRMING | Devuelve 202, sin otra llamada |
| WAITING_FOR_PHONE | Devuelve 202, sin otra llamada |
| CONFIRMATION_UNCERTAIN | Devuelve 202, sin otra llamada; requiere reconciliación |
| EXPIRED | Devuelve 409, sin llamada |
| REJECTED | Devuelve 409, sin llamada |
| CREATING / UNCERTAIN de creación | 409 HOLD_NOT_CONFIRMABLE; falta un hold conocido |

CREATING, UNCERTAIN, CONFIRMING, WAITING_FOR_PHONE y CONFIRMATION_UNCERTAIN bloquean la creación de otro proceso del mismo usuario y slot. Repetir el requestId original de creación devuelve el estado actual, sin reiniciar el flujo.

## Rechazos y respuestas inciertas

Se reconoce 404 HOLD_EXPIRED como EXPIRED. Son rechazos definitivos: 400 INVALID_SLOT/EXTERNAL_PATIENT_MISMATCH/VALIDATION_ERROR, 404 PROFESSIONAL_NOT_FOUND y 409 SLOT_ALREADY_RESERVED; se conserva el código seguro y REJECTED.

HOLD_NOT_FOUND y RESERVATION_PROCESS_ALREADY_CONFIRMED no prueban que no exista una reserva; quedan como CONFIRMATION_UNCERTAIN. También se conserva este estado ante timeout, desconexión, 5xx, redirección, respuesta inesperada o incompleta, y credencial técnica ausente. No se muestran cuerpos remotos ni se registran nombres/tokens en mensajes de error.

Si el proceso se detiene o falla la persistencia después del POST remoto, puede quedar CONFIRMING. Repetir devuelve el proceso y no reenvía la confirmación. Un 503 local de persistencia no demuestra que cátedra haya rechazado la operación.

El vencimiento anterior del hold no se usa para expirar automáticamente CONFIRMING/CONFIRMATION_UNCERTAIN: cátedra puede haber extendido el plazo o avanzado el proceso. Tampoco se convierte WAITING_FOR_PHONE en un resultado final solo por reloj. La reconciliación REST/Kafka y la expiración del proceso pendiente corresponden al siguiente bloque. No se ofrece un endpoint para forzar reintentos inseguros.

## Prueba manual

1. Iniciar sesión con el flujo CSRF y obtener JWT propio.
2. Crear un hold vigente y guardar el id local.
3. Enviar el POST de confirmación con ese id, nombres y JWT propio.
4. Consultar GET /api/reservation-processes/{id-local}.
5. Repetir el mismo POST: debe devolver el estado existente sin generar otro envío.

Se reutilizan BOOKING_CATEDRA_BASE_URL y BOOKING_CATEDRA_TOKEN. Aplicar V3 al reiniciar o reconstruir booking. Una prueba contra cátedra genera efectos reales; la suite utiliza únicamente HTTP local y MySQL temporal.

La recepción de AdditionalInformationRequested se agrega en la [issue #16](../solicitud-telefono/SPEC.md). El envío del teléfono y el resultado final siguen pendientes. WAITING_FOR_PHONE no equivale a CONFIRMED.
