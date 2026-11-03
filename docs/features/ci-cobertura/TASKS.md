# Tareas

- [x] Configurar JaCoCo y SonarScanner Maven para booking.
- [x] Preparar workflow Java 25, pruebas y envío de cobertura.
- [x] Verificar pruebas e informes locales.
- [ ] Confirmar proyecto, clave y SONAR_TOKEN remoto.
- [ ] Verificar análisis real del PR y recepción de cobertura.

Validación local 03/11/2026: `./mvnw -B -ntp clean verify`, BUILD SUCCESS; 5 pruebas sin fallos. Informes XML y HTML presentes y XML parseable. Pendientes los pasos de SonarCloud y la ejecución del workflow en GitHub.
