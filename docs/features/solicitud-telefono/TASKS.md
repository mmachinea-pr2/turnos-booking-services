# Tareas — issue #16

- [x] Incorporar localmente la confirmación de holds necesaria para el flujo.
- [x] Agregar configuración Kafka a aplicación, ejemplo de entorno y Compose.
- [x] Validar contrato y clave del evento, sin exigir groupId ausente del contrato.
- [x] Persistir inbox y solicitud aplicada mediante Flyway V4 y transacciones.
- [x] Evitar efectos duplicados y regresiones por mensajes anteriores.
- [x] Recuperar eventos pendientes cuando se puedan correlacionar.
- [x] Guardar eventos de otros tipos para su implementación posterior.
- [x] Exponer la solicitud solo al propietario activo del proceso.
- [x] Agregar pruebas de MySQL, autorización, concurrencia y offsets Kafka.
- [x] Documentar configuración, diagnóstico y límites del bloque.
- [ ] Verificar contra cátedra con un proceso real y Kafka habilitado.

El commit, push y PR los realiza la autora. Envío de teléfono y estados finales quedan fuera de esta issue.
