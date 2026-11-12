<!-- calidad:inicio -->
![Calidad](https://img.shields.io/badge/Calidad-64%2F100-green) ![Aprobar](https://img.shields.io/badge/Aprobar-no-red) ![Destacar](https://img.shields.io/badge/Destacar-no-lightgrey) ![Build](https://img.shields.io/badge/Build-OK-green) ![Duplicación](https://img.shields.io/badge/Duplicaci%C3%B3n-0.0%25-green) ![%POO](https://img.shields.io/badge/%25POO-89%25-green) ![Sueltas](https://img.shields.io/badge/Sueltas-1-red) ![D1](https://img.shields.io/badge/D1-2-red) ![D2](https://img.shields.io/badge/D2-36-red) ![D4](https://img.shields.io/badge/D4-70-yellow) ![D5](https://img.shields.io/badge/D5-100-green) ![D6](https://img.shields.io/badge/D6-100-green) ![D7](https://img.shields.io/badge/D7-100-green)

**Calidad (heurístico, 7 dimensiones):** índice **64/100** · build **OK** · aprobar **no (7/11)** · destacar **no**
Detalle completo en [`docs/EVALUACION.md`](docs/EVALUACION.md).
<!-- calidad:fin -->

# Servicio de turnos y reservas

Construye la disponibilidad y gestiona los procesos de reserva de cada usuario. Consulta las reglas vigentes al servicio de catálogo y las ocupaciones al servicio de la cátedra.

Proyecto integrador individual de Programación II, 2026. Se utilizan exclusivamente datos ficticios.

## Estado actual

Base ejecutable con Spring Boot, salud, MySQL propio, Flyway y Docker Compose. Incluye registro y login con Argon2id y JWT, disponibilidad, creación y confirmación de holds, intercambio de teléfono y resultados por Kafka, cancelaciones, consulta de reservas propias y reconciliación manual por REST. El flujo principal de reserva, teléfono por Kafka, confirmación, consulta propia y cancelación se validó contra cátedra el 2026-11-11. Los escenarios de fallos tienen evidencia automatizada local; no se consideran todos verificados en el servidor real.

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

Las pruebas usan contenedores MySQL y Kafka temporales y un puerto HTTP aleatorio; no leen la base de desarrollo ni requieren credenciales de cátedra. Verifican migraciones, salud, autenticación, autorización JWT/CORS, disponibilidad, holds, confirmación y recepción de eventos Kafka. La clave de pruebas no es una credencial de desarrollo.

Se genera `target/turnos-booking-services-0.0.1-SNAPSHOT.jar`, ejecutable con `java -jar` después de exportar la configuración de base.

## Migraciones

Flyway está habilitado y Hibernate utiliza `validate`, nunca `create` ni `update`. Las migraciones de `src/main/resources/db/migration` crean usuarios (V1), procesos de reserva (V2), datos de confirmación (V3) e inbox de eventos (V4) y envíos de teléfono pendientes (V5) y resultados de reservas (V6) y cancelaciones (V7). Las pruebas aplican esas mismas migraciones sobre MySQL temporal.

## Pendientes

Protección JWT de catálogo y validación integral de ambos backends contra cátedra. La reconciliación REST es manual y requiere una identidad remota conocida; no resuelve holds cuya respuesta de creación se perdió.

## CI y cobertura

`./mvnw -B -ntp clean verify` genera cobertura JaCoCo en `target/site/jacoco/index.html` y `jacoco.xml`. GitHub Actions ejecuta las pruebas en pushes a main y PR, y envía el análisis al proyecto propio de booking en SonarQube Cloud. Requiere configurar `SONAR_TOKEN` en este repositorio y vincular el proyecto; no se necesita para las pruebas locales. Ver [configuración y pasos de activación](docs/features/ci-cobertura/SPEC.md).

## Usuarios y seguridad

Antes de registro/login: GET `/api/csrf`, conservar la cookie y enviar el token recibido en `X-CSRF-TOKEN`. Registro: POST `/api/register`. Login: POST `/api/authenticate`. Perfil propio: GET `/api/account` con Bearer JWT. Contratos, validaciones, variables y límites: [usuarios y JWT](docs/features/usuarios-jwt/SPEC.md). La salud continúa pública. No usar el token técnico de cátedra para iniciar sesión en booking.

### Disponibilidad de turnos

GET `/api/availability?professionalId=15&date=2026-11-09` con JWT de usuario. Combina horarios de catálogo y ocupaciones de cátedra sin replicar el catálogo. Requiere configurar URLs y JWT técnico; un fallo de las fuentes devuelve 503. Ver [contrato, configuración y pruebas](docs/features/disponibilidad/SPEC.md).

### Bloqueos temporales de turnos

POST `/api/appointment-holds` crea un hold para el usuario autenticado con una clave `requestId` UUID. GET `/api/reservation-processes/{id}` permite consultar exclusivamente sus procesos. Los reintentos conservan la misma clave; una respuesta incierta no provoca otro POST remoto. Ver [contrato, estados y recuperación](docs/features/holds/SPEC.md). La creación de un hold aún no confirma una reserva.

### Confirmación inicial

POST `/api/reservation-processes/{id-local}/confirm` con JWT propio, patientFirstName y patientLastName inicia la confirmación de un hold vigente del usuario. El resultado WAITING_FOR_PHONE espera el intercambio por Kafka; todavía no es una reserva confirmada. Ver [contrato, reintentos y estados](docs/features/confirmacion-holds/SPEC.md).

### Solicitudes de teléfono por Kafka

El consumidor guarda `AdditionalInformationRequested` en MySQL y actualiza el proceso
sin repetir efectos ante duplicados. GET `/api/reservation-processes/{id-local}/phone-request`
devuelve la última solicitud al propietario autenticado (204 si todavía no llegó).
Kafka está desactivado por defecto; habilitar `BOOKING_KAFKA_ENABLED=true` con ZeroTier
conectado. Los resultados conocidos se procesan y los tipos desconocidos quedan guardados. Ver [configuración y comportamiento](docs/features/solicitud-telefono/SPEC.md).

### Envío del teléfono

POST `/api/reservation-processes/{id-local}/phone` recibe `requestEventId` y `phoneNumber`
con JWT propio. Guarda el evento antes de publicarlo por Kafka y reintenta los fallos
conservando su identificador. GET `/api/reservation-processes/{id-local}/phone-submission`
permite consultar la entrega. `PUBLISHED` confirma recepción en Kafka, no la reserva.
Requiere Kafka habilitado para publicar; ver [contrato, configuración y recuperación](docs/features/enviar-telefono/SPEC.md).

### Resultados de reservas

El consumidor procesa confirmaciones, cancelaciones, expiraciones, procesos inválidos
y rechazos de teléfono. GET `/api/reservation-processes/{id-local}/result` permite al
propietario consultar el resultado y el identificador de reserva. Si se rechaza el
telefono, puede corregirse mediante `rejectionEventId`, sin reiniciar el proceso.
Ver [estados, correcciones y recuperación](docs/features/resultados-reservas/SPEC.md).

### Cancelación de reservas

POST `/api/reservation-processes/{id-local}/cancel` permite al propietario cancelar
una reserva confirmada, con motivo opcional de hasta 500 caracteres. El intento se
guarda antes de llamar a cátedra. GET `/api/reservation-processes/{id-local}/cancellation`
permite consultar su resultado, incluso si la respuesta remota fue incierta.
Ver [contrato y coordinación REST/Kafka](docs/features/cancelar-reservas/SPEC.md).

### Consulta y recuperación de reservas

GET `/api/appointments?page=0&size=20` lista exclusivamente las reservas del usuario
autenticado. POST `/api/reservation-processes/{id-local}/reconcile` consulta cátedra
y recupera el estado local cuando hay una correspondencia inequívoca. No repite
solicitudes de creación, confirmación ni cancelación. Si no encuentra la reserva,
conserva la incertidumbre.
Ver [contrato, estados y límites](docs/features/consultar-recuperar-reservas/SPEC.md).

### Ejecutar ambos backends en Docker

`compose.integration.yaml` levanta catálogo y booking con bases separadas, JWT
compartido y comunicación interna `http://catalog:8080`. Requiere el repositorio
hermano de catálogo con seguridad JWT. Se configura con `.env.integration`, a partir
de `.env.integration.example`; publica puertos locales 18080 y 18081.

La prueba `bash scripts/validate-integration.sh` utiliza exclusivamente un simulador
REST y Kafka local, datos ficticios y claves descartables. No crea reservas en cátedra.
Ver [ejecución conjunta y validación](docs/features/integracion-docker/SPEC.md).

## Documentación de arquitectura e integración

- [Arquitectura, despliegue y decisiones de seguridad](docs/arquitectura.md).
- [Contratos HTTP entre booking y catálogo](docs/contratos-entre-servicios.md).
- [Modelo de datos y propiedad de cada servicio](docs/modelo-datos.md).
- [Evidencia de validación y alcance de las pruebas](docs/features/integracion-docker/VALIDATION.md).
