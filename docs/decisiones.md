# Decisiones técnicas

- Java 25, Spring Boot 4.1.1 y Maven Wrapper 3.10.0: mismas versiones que catálogo para mantener un entorno coherente. No se genera con JHipster; sus requisitos de usuarios se implementarán explícitamente cuando se aborde seguridad.
- Paquete base `ar.edu.programacion2.booking`, aplicación y artefacto propios. No se importa código interno ni persistencia de catálogo.
- Puerto local 8081 y variables BOOKING_APP_HOST/BOOKING_APP_PORT: ejecución simultánea con catálogo, que usa 8080 por defecto.
- Actuator publica únicamente salud, sin detalles. Incluye la conectividad a su propia base; no mide integraciones externas todavía inexistentes.
- MySQL 8.4.8 usa una base, usuario y volumen propios. El puerto local 3309 evita colisiones con catálogo (3308). No se utiliza H2 como sustituto. Flyway gestiona migraciones y Hibernate solo valida. No se crean tablas de negocio ficticias para esta etapa.
- JWT, propiedad de usuarios, Argon2id, reservas e integración REST/Kafka son trabajo pendiente. No se afirma que la base inicial cumpla todavía esos requisitos.
- CI y cobertura están configurados con Java 25 y JaCoCo. El envío a SonarCloud usa una clave propia de booking y requiere completar la vinculación y el secreto remoto; no se reutiliza la clave de proyecto de catálogo.
