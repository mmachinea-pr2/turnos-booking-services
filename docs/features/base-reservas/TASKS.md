# Tareas

- [x] Crear aplicación Java 25 con Maven Wrapper.
- [x] Configurar puerto propio y salud sin detalles internos.
- [x] Agregar pruebas HTTP de salud y exposición de Actuator.
- [x] Documentar ejecución y decisiones.
- [x] Incorporar MySQL con datos y credenciales propios.
- [x] Incorporar Flyway y pruebas con base temporal.
- [x] Preparar Docker Compose y verificar el arranque.

Validación del primer incremento (03/11/2026): `./mvnw -B -ntp clean verify`, BUILD SUCCESS. Cuatro pruebas HTTP sin fallos, errores ni omisiones; JAR ejecutable generado.

Validación del segundo incremento (03/11/2026): `./mvnw -B -ntp clean verify`, BUILD SUCCESS; 5 pruebas sin fallos, errores ni omisiones. Imagen construida con `docker compose build booking`. MySQL saludable y backend en Docker respondiendo HTTP 200 con `{"status":"UP"}` en 8081. Proceso Java con UID 10001. En la base de desarrollo solo existe `flyway_schema_history`, sin migraciones de negocio ni tabla de prueba. `.env` está ignorado y excluido de la imagen.
