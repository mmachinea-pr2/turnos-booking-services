# Decisiones técnicas

- Java 25, Spring Boot 4.1.1 y Maven Wrapper 3.10.0: mismas versiones que catálogo para mantener un entorno coherente. No se genera con JHipster; sus requisitos de usuarios se implementarán explícitamente cuando se aborde seguridad.
- Paquete base `ar.edu.programacion2.booking`, aplicación y artefacto propios. No se importa código interno ni persistencia de catálogo.
- Puerto local 8081 y variables BOOKING_APP_HOST/BOOKING_APP_PORT: ejecución simultánea con catálogo, que usa 8080 por defecto.
- Actuator publica únicamente salud, sin detalles. Por ahora mide el arranque de este servicio, no la disponibilidad de integraciones todavía inexistentes.
- La base servidor será MySQL, con usuario, base, volumen y migraciones propios. Se agrega en el segundo incremento; no se utiliza H2 como sustituto.
- JWT, propiedad de usuarios, Argon2id, reservas e integración REST/Kafka son trabajo pendiente. No se afirma que la base inicial cumpla todavía esos requisitos.
- CI, cobertura y SonarCloud se configurarán para este backend con su propia identidad de proyecto; no se reutiliza la clave de Sonar de catálogo.
