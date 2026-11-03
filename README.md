<!-- calidad:inicio -->
![Calidad](https://img.shields.io/badge/Calidad-sin%20c%C3%B3digo-lightgrey) ![Aprobar](https://img.shields.io/badge/Aprobar-no-red) ![Destacar](https://img.shields.io/badge/Destacar-no-lightgrey)

**Calidad (7 dimensiones):** sin código en `main` (no evaluable).
<!-- calidad:fin -->

# Servicio de turnos y reservas

Construye la disponibilidad y gestiona los procesos de reserva de cada usuario. Consulta las reglas vigentes al servicio de catálogo y las ocupaciones al servicio de la cátedra.

Proyecto integrador individual de Programación II, 2026. Se utilizan exclusivamente datos ficticios.

## Estado actual

Primera base ejecutable: Spring Boot con endpoint de salud. Todavía no implementa reservas, autenticación, persistencia ni integraciones externas.

## Alcance previsto

- Combinar horarios del catálogo con ocupaciones de la cátedra.
- Solicitar y confirmar bloqueos temporales de turnos por REST.
- Recibir solicitudes de teléfono y enviar la respuesta por Kafka.
- Procesar confirmaciones, rechazos, vencimientos, procesos inválidos y cancelaciones.
- Asociar cada proceso a su usuario e impedir el acceso a reservas ajenas.
- Recuperar operaciones pendientes tras fallos o reinicios.
- Consultar el catálogo mediante un contrato protegido con JWT, sin acceder a su base de datos.

## Tecnologías

Java 25 (Temurin), Spring Boot 4.1.1, Maven Wrapper 3.10.0, Spring MVC, Actuator y JUnit 5. Se mantienen las versiones utilizadas en catálogo. MySQL y Flyway se incorporarán en el siguiente incremento, con datos y credenciales propios.

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

Requisito: JDK 25. No hace falta instalar Maven ni tener Docker o la cátedra disponibles para esta primera etapa.

```bash
sdk env
./mvnw spring-boot:run
```

Si no usás SDKMAN, seleccioná JDK 25 en IntelliJ y abrí `pom.xml` como proyecto Maven. Ejecutá `BookingApplication`.

El servicio escucha por defecto en `127.0.0.1:8081`, separado del puerto de catálogo:

```bash
curl http://localhost:8081/actuator/health
```

Respuesta esperada: `{"status":"UP"}`. Solo se expone el endpoint de salud de Actuator, sin detalles internos. Todavía no existen endpoints de negocio. La seguridad se incorporará antes de exponer operaciones protegidas.

Variables opcionales: `BOOKING_APP_HOST` (127.0.0.1) y `BOOKING_APP_PORT` (8081). Spring no carga `.env` automáticamente; exportá las variables o configuralas en IntelliJ.

```bash
./mvnw -B -ntp clean verify
java -jar target/turnos-booking-services-0.0.1-SNAPSHOT.jar
```

Las pruebas levantan un servidor HTTP en un puerto aleatorio, comprueban salud y verifican que Actuator no exponga configuración ni variables de entorno. `verify` genera el JAR ejecutable.

## Próximo incremento

MySQL propio, Flyway, pruebas con base temporal y Docker Compose. El primer commit no crea tablas de reservas ni accede a la base de catálogo.
