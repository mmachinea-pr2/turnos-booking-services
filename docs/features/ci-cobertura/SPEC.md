# CI, cobertura y SonarQube Cloud

Issue #3, rama `3-cofigurar-ci-cobertura-y-sonarqube`, milestone Base del servicio de reservas. PR hacia main.

## Configuración

JaCoCo 0.8.15 instrumenta las pruebas y genera HTML y XML durante `verify`. SonarScanner Maven 5.8.0.7211 consume `target/site/jacoco/jacoco.xml`. Se mantienen las versiones de herramientas ya usadas en catálogo, con identidad independiente para booking:

- Organización: `mmachinea-pr2`.
- Clave prevista del proyecto: `mmachinea-pr2_turnos-booking-services`. Confirmar que coincida con el proyecto importado en SonarQube Cloud.
- Secreto GitHub: `SONAR_TOKEN`, configurado en este repositorio. No se guarda en archivos ni argumentos.

El workflow se ejecuta en pushes a main y PR hacia main, con Java 25 Temurin y Ubuntu 24.04. Testcontainers levanta MySQL temporal usando Docker del runner; no depende de `.env`, la base de desarrollo ni cátedra. Descarga el historial completo, ejecuta `clean verify`, comprueba el XML y envía el análisis. El paso de Sonar espera el Quality Gate y falla si este no pasa. Las pruebas también fallan el job si encuentran errores.

Los PR desde forks ejecutan pruebas pero no reciben el token ni ejecutan el envío a Sonar. En ramas del propio repositorio, la falta de SONAR_TOKEN falla con un mensaje explícito. No se usa pull_request_target ni se imprimen secretos.

## Configuración remota pendiente

1. Importar `mmachinea-pr2/turnos-booking-services` como proyecto de SonarQube Cloud, dentro de la organización propia.
2. Confirmar la clave indicada arriba o ajustar pom.xml si el asistente asignó otra.
3. Agregar SONAR_TOKEN en Settings > Secrets and variables > Actions de este repositorio, con permiso de análisis sobre el proyecto.
4. Elegir análisis por GitHub Actions/Maven y desactivar Automatic Analysis para este proyecto si está activo.
5. Publicar la rama y abrir el PR hacia main. Comprobar el job y la recepción de cobertura en Sonar.

Estas tareas externas no se dan por completadas solo por tener el workflow en Git. Un token configurado en catálogo no se copia automáticamente a booking.

## Verificación local

```bash
./mvnw -B -ntp clean verify
```

Reporte HTML: `target/site/jacoco/index.html`. XML: `target/site/jacoco/jacoco.xml`. No requieren token. Los informes están excluidos de Git por `target/`.

No se excluyen clases propias para aumentar cobertura ni se agrega un umbral local inventado. La aplicación actual solo tiene la clase de arranque: la cobertura irá representando lógica de negocio a medida que se implemente. Un porcentaje no demuestra que reservas esté terminado.
