# Separación de reglas y persistencia de reconciliación

Issue: #31.

## Problema
AppointmentReconciliation mezclaba reglas de identidad y transición de estados con SQL.
La evaluación señaló complejidad y responsabilidades concentradas en esa clase.

## Diseño
- ReconciliationPolicy decide sobre datos tipados, sin Spring, JDBC ni red.
- ReconciliationState representa el estado local relevante; ReconciliationDecision expresa el resultado y sus efectos.
- ReconciliationStore es el puerto de salida que requiere la aplicación.
- JdbcReconciliationStore implementa el acceso MySQL y la transacción.
- AppointmentReconciliation coordina el puerto y la política, traduciendo un dato remoto incompatible al error HTTP existente mediante AvailabilityUnavailableException.

El puerto recibe una función de decisión que el adaptador ejecuta después de leer la fila del usuario con FOR UPDATE. La lectura bloqueada, la decisión y la escritura de estado, cancelación y outbox permanecen en una sola transacción. La función no hace llamadas de red.
La consulta REST sigue ocurriendo antes de esa transacción. El updated_at observado antes de consultar cátedra se compara con el actual bajo bloqueo. Si cambió, retorna CONCURRENT_CHANGE sin modificar datos.

No cambian endpoints, respuestas, esquema SQL ni estados públicos. Los estados terminales no retroceden y los datos remotos deben coincidir con usuario, proceso, profesional, fecha, hora e identificador de reserva cuando ya existe.

## Alcance
Esta separación corresponde a reconciliación. El resto de booking todavía conserva servicios y repositorios concretos; no implica una migración completa del backend a arquitectura hexagonal.

## Validación
Las pruebas de dominio cubren identidad, transiciones, estados terminales y prioridad de concurrencia sin infraestructura. Las pruebas de integración existentes comprueban aislamiento por usuario, cambios de Kafka durante la consulta REST, cancelación y detención del outbox con MySQL real en Testcontainers.
