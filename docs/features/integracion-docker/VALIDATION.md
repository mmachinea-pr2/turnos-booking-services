# Evidencia de validación local

Resultado: **correcto**. Fecha del registro: 2026-11-10T23:55:59-03:00.

Código utilizado: booking base `3254a7a` más los archivos de integración de esta
issue; catálogo `4d1d15e` (JWT). Se construyeron ambas imágenes mediante sus
Dockerfiles, sin modificar código de producción Java.

Se ejecutaron los pasos de Compose de `scripts/validate-integration.sh` y el
verificador `src/test/integration/verify_docker.py`, utilizando credenciales
aleatorias temporales, bases propias y el proyecto `turnos-validation`.
El verificador finalizó con código 0 y este resumen, sin tokens ni datos personales:

```text
OK: login, JWT entre servicios y catálogo cargado
OK: disponibilidad, hold idempotente, propietario y teléfono por Kafka
OK: confirmación Kafka, consulta propia y cancelación REST
OK: persistencia tras reinicio, duplicado y reconciliación sin regresión
VALIDACIÓN LOCAL COMPLETA. Pendiente repetir contra cátedra real.
```

También se verificaron sintaxis Bash/Python, formato de Compose y `git diff --check`.
No se volvió a ejecutar la suite Maven porque no se modificaron clases Java;
las imágenes se construyeron y se probaron como aplicaciones desplegadas.

La API real de cátedra respondió HTTP 200 en su página inicial y se logró conexión
TCP a Redis 6379 y Kafka 9094. Esto solo demuestra conectividad desde el host en ese
momento: no verifica autenticación, metadatos Kafka ni eventos reales.

**Pendiente:** repetir el flujo autenticado completo contra cátedra, con datos
ficticios y cancelando la reserva de validación. No se creó ninguna reserva real.

Los contenedores y volúmenes de esta ejecución descartable fueron retirados al
finalizar. No se tocaron los contenedores ni bases de desarrollo existentes.

## Comprobación real de lectura — 2026-11-11

Con `.env.integration` local completado se habilitaron REST, Redis, recuperación
periódica y consumidores Kafka, y se levantó el proyecto conjunto real.

- Catálogo y booking respondieron UP.
- Registro y login de un usuario local ficticio funcionaron.
- El JWT de booking permitió consultar catálogo.
- El catálogo informó initialized=true y devolvió 8 categorías habilitadas.
- Se observaron asignaciones de particiones Kafka en ambos consumidores, sin los
  indicadores de error de conexión revisados. Esto no prueba emisión de eventos.
- La consulta real de disponibilidad respondió HTTP 200; el profesional y la fecha
  seleccionados devolvieron cero horarios disponibles.

No se crearon holds ni reservas en cátedra. Sigue pendiente probar el recorrido
completo de reserva, teléfono, confirmación y cancelación contra el servidor real.
Los servicios conjuntos quedaron en ejecución; las credenciales permanecen solo
en el archivo local ignorado por Git.

## Prueba manual interrumpida por indisponibilidad — 2026-11-11

En Postman se verificaron registro y autenticación de un usuario ficticio,
consulta de las ocho categorías con el JWT de booking, profesionales de la
categoría 101 y agenda del profesional 203 (viernes de 12:00 a 15:00, intervalos
de 20 minutos).

La consulta de disponibilidad para el profesional 203 el 2026-11-13 devolvió
`AVAILABILITY_UNAVAILABLE`. Las comprobaciones posteriores de la página raíz
y de `/api/appointment-occupancies` de cátedra terminaron por timeout desde el
host; la página raíz tampoco respondió desde el contenedor de booking. La ruta
del host utilizaba la interfaz ZeroTier y los cuatro contenedores propios
figuraban saludables. La alumna informó que se anunció la caída del servidor.

Este resultado no equivale a una agenda sin turnos: no se pudo obtener la
ocupación vigente. No se creó ningún hold ni reserva. La validación real
completa permanece pendiente; la validación con simulador no la reemplaza.

Al restablecerse el servicio, autenticar nuevamente al usuario local si venció
su JWT, consultar una fecha futura compatible con la agenda vigente y retomar
el recorrido documentado en `SPEC.md`. No reutilizar un horario sin comprobar
su disponibilidad actual.

## Flujo principal completado contra cátedra — 2026-11-11

Evidencia: respuestas de Postman compartidas por la alumna durante la ejecución
manual con datos ficticios. Esta ejecución posterior completa el recorrido que
había quedado interrumpido en las secciones anteriores.

1. Autenticación local y consultas de catálogo con el JWT de booking.
2. Disponibilidad del profesional 203 para el 2026-11-13: nueve intervalos de
   veinte minutos entre 12:00 y 15:00.
3. Creación de hold para 12:00: proceso local
   `7f99eabe-cccd-4fad-96cf-ecc869809cd6`, estado `HELD`.
4. Confirmación REST: `WAITING_FOR_PHONE`.
5. Recepción Kafka: la consulta de solicitud de teléfono devolvió
   `requiredField=PHONE_NUMBER` y su identificador de evento.
6. Envío del teléfono ficticio: outbox inicialmente `PENDING`.
7. Recepción de `AppointmentConfirmed`: reserva `5359`, estado `CONFIRMED`,
   evento ocurrido a las `2026-11-11T23:44:26.282839Z`.
8. Consulta autenticada de reservas: un elemento, reserva `5359`, mismo proceso,
   profesional y horario, asociado al UUID del usuario que inició la operación.
9. Cancelación de la reserva de prueba: proceso y cancelación `CANCELLED`,
   `cancelledAt=2026-11-11T23:46:08.614947Z`, `errorCode=null`.

El resultado confirmado demuestra que el intercambio de teléfono y resultado
por Kafka se completó para este proceso, aunque la primera lectura de la outbox
lo mostraba pendiente. La reserva de prueba terminó cancelada.

No se incluyeron JWT, contraseñas ni teléfonos en esta evidencia. No se repitieron
en esa ejecución contra cátedra los escenarios de expiración, acceso cruzado, mensajes duplicados,
reinicio con operaciones pendientes o discontinuidad del catálogo. Sus pruebas
locales y la validación del flujo principal real son evidencias de distinto alcance.

## Expiración sin enviar teléfono — 2026-11-11 (hora argentina)

Evidencia: respuestas de Postman compartidas por la alumna. Se creó un nuevo
hold del profesional 203 para el 2026-11-13 a las 12:20, proceso local
`c22fc212-0676-490b-9f6b-cb790aac7461`.

- Creación: `HELD`.
- Confirmación REST: `WAITING_FOR_PHONE`.
- Solicitud recibida por Kafka: `PHONE_NUMBER`, con vencimiento
  `2026-11-12T01:17:26.946930205Z` (22:17:26 del 11 de noviembre, UTC-03:00).
- No se envió el teléfono.
- La consulta de resultado respondió HTTP 200 con estado `EXPIRED`, evento
  `AppointmentProcessExpired`, motivo `HOLD_EXPIRED` y `reservationId=null`.
- Evento `process-event-1adb136d-c32d-41c7-85ca-5e89256be181`, ocurrido a las
  `2026-11-12T01:17:36.799050Z`, correlacionado con la solicitud de teléfono.

Esto confirma la recepción del vencimiento desde cátedra y su aplicación al
estado local. No se confirmó una reserva ni es necesario cancelar este proceso
expirado. Esta prueba no mide el comportamiento frente a duplicados o reinicios,
ni comprueba por sí sola la liberación del horario en la consulta de ocupaciones.
