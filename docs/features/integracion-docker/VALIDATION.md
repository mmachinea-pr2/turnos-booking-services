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
