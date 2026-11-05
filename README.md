<!-- calidad:inicio -->
![Calidad](https://img.shields.io/badge/Calidad-sin%20c%C3%B3digo-lightgrey) ![Aprobar](https://img.shields.io/badge/Aprobar-no-red) ![Destacar](https://img.shields.io/badge/Destacar-no-lightgrey)

**Calidad (7 dimensiones):** sin código en `main` (no evaluable).
<!-- calidad:fin -->

# Servicio de turnos y reservas

Construye la disponibilidad y gestiona los procesos de reserva de cada usuario. Consulta las reglas vigentes al servicio de catálogo y las ocupaciones al servicio de la cátedra.

Proyecto integrador individual de Programación II, 2026. Se utilizan exclusivamente datos ficticios.

## Estado actual

Base ejecutable con Spring Boot, salud, MySQL propio, Flyway y Docker Compose. Incluye registro, login Argon2id y JWT. Todavía no implementa reservas ni integraciones externas.

## Alcance previsto

- Combinar horarios del catálogo con ocupaciones de la cátedra.
- Solicitar y confirmar bloqueos temporales de turnos por REST.
- Recibir solicitudes de teléfono y enviar la respuesta por Kafka.
- Procesar confirmaciones, rechazos, vencimientos, procesos inválidos y cancelaciones.
- Asociar cada proceso a su usuario e impedir el acceso a reservas ajenas.
- Recuperar operaciones pendientes tras fallos o reinicios.
- Consultar el catálogo mediante un contrato protegido con JWT, sin acceder a su base de datos.

## Tecnologías

Java 25 (Temurin), Spring Boot 4.1.1, Maven Wrapper 3.10.0, Spring MVC, Actuator y JUnit 5. Se mantienen las versiones utilizadas en catálogo. MySQL 8.4.8 y Flyway administran una base propia. Las pruebas usan Testcontainers con MySQL real.

Cada backend será propietario de su base lógica o esquema, usuario y migraciones. Docker Compose deberá levantar el servicio y su infraestructura local. No se utilizará una base embebida como persistencia principal.

## Repositorios del sistema

- [Aplicación Android](https://github.com/mmachinea-pr2/turnos-mobile)
- [Catálogo y sincronización](https://github.com/mmachinea-pr2/turnos-catalog-service)
- [Turnos y reservas](https://github.com/mmachinea-pr2/turnos-booking-services)

## Documentación

La [planificación](https://github.com/mmachinea-pr2/turnos-catalog-service/blob/main/docs/planificacion.md) y las [decisiones iniciales](https://github.com/mmachinea-pr2/turnos-catalog-service/blob/main/docs/decisiones.md) se mantendrán en el repositorio de catálogo. Las decisiones específicas de este servicio se documentan en [decisiones técnicas](docs/decisiones.md).

## Fuentes de requisitos

- `PROJECT_STATEMENT-v1.md`: alcance y requisitos de la cátedra.
- `INTEGRATION_REFERENCE-v2.md`: contratos externos REST, Redis y Kafka.

## Ejecución y pruebas

Requisitos: JDK 25, Docker y Docker Compose. Maven se ejecuta con el Wrapper del repositorio.

### Configuración local

Si todavía no existe `.env`, copiar `.env.example` a `.env` y completar BOOKING_DB_PASSWORD y BOOKING_MYSQL_ROOT_PASSWORD con contraseñas diferentes. Completar también BOOKING_JWT_SECRET con Base64 de al menos 32 bytes aleatorios (ver documentación de seguridad). No sobrescribir un `.env` existente. Nunca publicarlo: está excluido de Git y del contexto Docker.

Valores por defecto: base `booking`, usuario `booking_app`, MySQL local `3309` y HTTP `8081`. El proyecto Compose `machinea-turnos-booking` tiene su propio volumen `booking_mysql_data`, independiente de catálogo. No usa el usuario root para la aplicación.

### Aplicación en IntelliJ o terminal

```bash
docker compose up -d --wait mysql
sdk env
set -a
. ./.env
set +a
unset BOOKING_MYSQL_ROOT_PASSWORD
./mvnw spring-boot:run
```

En IntelliJ, abrir `pom.xml` como proyecto Maven, seleccionar JDK 25 y ejecutar `BookingApplication`. Cargar las variables BOOKING_DB_NAME, BOOKING_DB_USER, BOOKING_DB_PASSWORD y BOOKING_JWT_SECRET en su configuración de ejecución; Spring no lee `.env` automáticamente. HOST y PORT usan los valores locales por defecto.

```bash
curl http://localhost:8081/actuator/health
```

Respuesta esperada: `{"status":"UP"}`. Salud incluye conexión a MySQL, sin mostrar detalles internos. Si la base falla, salud puede responder DOWN. Los endpoints de usuarios se detallan en [usuarios y JWT](docs/features/usuarios-jwt/SPEC.md).

### Aplicación y base en Docker

Detener antes la instancia local de booking si ocupa 8081:

```bash
docker compose up --build -d
docker compose ps
curl http://localhost:8081/actuator/health
```

Para probar en otro puerto sin detener otra aplicación, usar `BOOKING_APP_PORT=18082 docker compose up --build -d` y consultar ese puerto. Dentro de Docker el backend usa MySQL por nombre de servicio y puerto 3306. Las credenciales se inyectan al ejecutar, no al construir la imagen; el proceso Java corre sin root.

`docker compose down` detiene y elimina contenedores conservando los datos. No agregar `-v` salvo que se quiera borrar la base. Las variables de creación de usuario y contraseña de MySQL se aplican al inicializar un volumen vacío; cambiarlas luego no cambia automáticamente las credenciales del volumen existente.

### Pruebas y paquete

```bash
./mvnw -B -ntp clean verify
```

Las pruebas usan un contenedor MySQL temporal y un puerto HTTP aleatorio; no leen la base de desarrollo ni requieren credenciales de cátedra. Verifican salud, migración real de usuarios, registro, autenticación, autorización y validación JWT/CORS. La clave de pruebas no es una credencial de desarrollo.

Se genera `target/turnos-booking-services-0.0.1-SNAPSHOT.jar`, ejecutable con `java -jar` después de exportar la configuración de base.

## Migraciones

Flyway está habilitado y Hibernate utiliza `validate`, nunca `create` ni `update`. La migración V1 de `src/main/resources/db/migration` crea usuarios propios de booking. Las pruebas usan esta misma migración sobre MySQL temporal; se retiró setup_probe de los recursos de test.

## Pendientes

Modelo de reservas, protección JWT de catálogo, consultas entre servicios e integraciones REST/Kafka pendientes.

## CI y cobertura

`./mvnw -B -ntp clean verify` genera cobertura JaCoCo en `target/site/jacoco/index.html` y `jacoco.xml`. GitHub Actions ejecuta las pruebas en pushes a main y PR, y envía el análisis al proyecto propio de booking en SonarQube Cloud. Requiere configurar `SONAR_TOKEN` en este repositorio y vincular el proyecto; no se necesita para las pruebas locales. Ver [configuración y pasos de activación](docs/features/ci-cobertura/SPEC.md).

## Usuarios y seguridad

Antes de registro/login: GET `/api/csrf`, conservar la cookie y enviar el token recibido en `X-CSRF-TOKEN`. Registro: POST `/api/register`. Login: POST `/api/authenticate`. Perfil propio: GET `/api/account` con Bearer JWT. Contratos, validaciones, variables y límites: [usuarios y JWT](docs/features/usuarios-jwt/SPEC.md). La salud continúa pública. No usar el token técnico de cátedra para iniciar sesión en booking.
