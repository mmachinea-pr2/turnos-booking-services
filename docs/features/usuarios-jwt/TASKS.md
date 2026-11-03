# Tareas de la issue #5

- [x] Usuarios propios y migración Flyway real.
- [x] Registro validado, normalización y restricciones únicas.
- [x] Argon2id y login con errores seguros.
- [x] Emisión y validación JWT; identidad por sub.
- [x] API protegida y cuenta autenticada.
- [x] Clave externa, Compose y CORS explícito.
- [x] Contrato previsto para catálogo y documentación.
- [x] Verificar suite completa e informes de cobertura.
- [x] Verificar arranque Docker con nueva configuración.

La protección efectiva de catálogo y las reservas por propietario pertenecen a los siguientes bloques; no están implementadas en esta issue.

Validación 03/11/2026: `./mvnw -B -ntp clean verify`, BUILD SUCCESS; 43 pruebas sin fallos, errores ni omisiones. JaCoCo: 122/128 líneas y 51/58 ramas cubiertas. Docker: imagen construida, salud HTTP 200 UP, cuenta anónima 401, migración V1 aplicada y cero usuarios de desarrollo. Se incorporó por fast-forward el commit existente `b3f0387` de CI, ausente en la base original de la rama. Integrar primero su PR en main si continúa pendiente. No se crearon commits ni se publicó la rama durante esta implementación.

Corrección de CSRF del PR #6 (03/11/2026): protección activa con excepciones limitadas a POST de registro y autenticación; Resource Server gestiona las solicitudes Bearer. Pruebas adicionales verifican filtro activo, escritura autenticada y rechazo de solicitudes anónimas o con JWT en cookie. `./mvnw -B -ntp clean verify`: BUILD SUCCESS, 45 pruebas sin fallos, errores ni omisiones. La nueva evaluación de Sonar queda pendiente de publicar el commit.
