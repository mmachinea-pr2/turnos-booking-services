# Disponibilidad de turnos

Issue #10. Rama `10-consultar-disponibilidad-de-turnos-por-profesional-y-fecha`.
Milestone: Disponibilidad y reservas.

## Consulta

`GET /api/availability?professionalId=15&date=2026-11-09`

Requiere JWT propio de usuario con ROLE_USER en Authorization: Bearer. El token técnico de cátedra no sirve para autenticar usuarios en booking. Respuesta 200 con Cache-Control: no-store:

```json
{"professionalId":15,"date":"2026-11-09","slots":[{"startTime":"09:00:00","endTime":"09:30:00"}]}
```

La fecha es una fecha local de agenda; se utiliza BOOKING_AVAILABILITY_ZONE (por defecto America/Argentina/Mendoza, UTC-03). Se rechazan identificadores no positivos, parámetros ausentes/mal formados y fechas anteriores a hoy con 400 INVALID_AVAILABILITY_REQUEST. Horarios de hoy que ya comenzaron no se ofrecen.

## Fuentes y cálculo

1. Consultar el estado de sincronización de catálogo. Si no está inicializado, no calcular disponibilidad.
2. Consultar GET /api/catalog/professionals/{id}/schedules propagando el JWT del usuario. Ese endpoint devuelve únicamente horarios, profesionales y categorías habilitados.
3. Consultar GET /api/appointment-occupancies con professionalId y from=to=date usando exclusivamente el JWT técnico.
4. Seleccionar las reglas del día semanal, generar slots completos hasta endTime (sin incluir fracciones finales), ordenar y deduplicar.
5. Excluir los inicios informados como CONFIRMED o HELD. Un hold aún informado por cátedra permanece bloqueado aunque su expiresAt parezca vencido; no se libera anticipadamente según el reloj de booking.

Una lista vacía es válida. El contrato actual de catálogo devuelve [] tanto para profesional inexistente/deshabilitado como para falta de horarios: booking conserva ese resultado, sin inventar un 404. No se persisten horarios ni se crea otra réplica del catálogo. Reglas con igual inicio y distinta duración se consideran ambiguas y producen error.

## Fallos y límites

503 AVAILABILITY_UNAVAILABLE si una fuente no responde, devuelve un error HTTP, redirección, cuerpo inválido, fechas/profesional que no coinciden, estados desconocidos o campos esenciales incompletos. No devolver horarios libres ante esos fallos. Sin reintentos automáticos. Timeout por solicitud: conexión 3 s, lectura 5 s. No se propagan cuerpos externos, tokens ni detalles internos en errores. No se siguen redirecciones con credenciales.

La disponibilidad es una observación, no una garantía de reserva. Hay carreras entre consultas y posibles retrasos de las fuentes. Se respeta el contrato de ocupaciones CONFIRMED/HELD de cátedra; este bloque no puede reconstruir procesos PHONE_PENDING que el servidor omita. El siguiente bloque debe crear un hold y tratar sus conflictos; esta issue no crea ni confirma reservas.

## Configuración y prueba manual

- BOOKING_CATALOG_BASE_URL: URL del servicio propio de catálogo; local http://localhost:8080.
- BOOKING_CATEDRA_BASE_URL: http://172.17.201.1:8090 para el entorno ZeroTier.
- BOOKING_CATEDRA_TOKEN: JWT técnico vigente, solo en .env ignorado o secretos del entorno. Vacío permite arrancar la app, pero disponibilidad devuelve 503.
- BOOKING_AVAILABILITY_ZONE: zona de agenda.
- BOOKING_DOCKER_CATALOG_BASE_URL: URL de catálogo alcanzable desde el contenedor, independiente de la URL utilizada al ejecutar desde IntelliJ.

En Postman, registrar/iniciar sesión siguiendo el intercambio CSRF documentado en usuarios-jwt. Usar el id_token del login en Authorization Bearer y consultar /api/availability con un profesional del catálogo y una fecha futura. Catálogo debe estar iniciado y sincronizado; cátedra requiere ZeroTier y credencial técnica vigente. El cliente de catálogo ya propaga JWT, pero la protección efectiva de ese repositorio corresponde a su propia issue.

### Docker con repositorios separados

Compose agrega host.docker.internal:host-gateway como alternativa para alcanzar servicios del host. Esto no hace accesible un puerto publicado exclusivamente en 127.0.0.1: la publicación local predeterminada de catálogo requiere configurar una conexión entre contenedores.

Para mantener los puertos locales y evitar exponer catálogo en todas las interfaces, se puede crear una red dedicada y conectar solo los contenedores de aplicación (no las bases). Con ambos compose levantados, ejecutar desde el directorio que contiene ambos repositorios:

```bash
docker network create turnos-integration
docker network connect --alias catalog-api turnos-integration "$(docker compose -f turnos-catalog-service/compose.yaml ps -q catalog)"
docker network connect turnos-integration "$(docker compose -f turnos-booking-services/compose.yaml ps -q booking)"
```

Antes de levantar booking, configurar BOOKING_DOCKER_CATALOG_BASE_URL=http://catalog-api:8080 en su .env. Si la red ya existe, no volver a crearla. Al recrear contenedores hay que volver a conectarlos; una configuración Compose conjunta persistente queda para la integración de despliegue. Verificar los nombres de servicio de cada compose antes de ejecutar. Esta issue no modifica el compose ni la seguridad de catálogo.

## Pruebas

Servidores HTTP locales simulan ambos contratos y comprueban credenciales, query parameters, respuestas inválidas y fallos. Pruebas del cálculo con reloj fijo y pruebas HTTP del endpoint con Spring Security y MySQL temporal. No se requiere el servidor docente para ejecutar la suite. La prueba contra la infraestructura real es independiente y queda pendiente de configuración y conectividad.
