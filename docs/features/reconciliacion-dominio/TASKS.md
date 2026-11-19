# Issue #31 — Reglas y persistencia de reconciliación

- [x] Extraer política de identidad y transiciones sin dependencias de infraestructura.
- [x] Definir puerto de salida y adaptador JDBC.
- [x] Conservar aislamiento por usuario, bloqueo de fila y comparación de updated_at.
- [x] Mantener atómicos los cambios en proceso, cancelación y outbox.
- [x] Documentar alcance y decisión.
- [x] Ejecutar Maven verify: 365 pruebas, sin fallos, errores ni omitidas (2026-11-17).

JaCoCo: 100% de líneas y ramas de ReconciliationPolicy, ReconciliationDecision y JdbcReconciliationStore; 100% de líneas de AppointmentReconciliation.
La validación utiliza MySQL y Kafka de Testcontainers, no crea reservas en cátedra.
