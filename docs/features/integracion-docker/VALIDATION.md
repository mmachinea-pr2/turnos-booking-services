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
