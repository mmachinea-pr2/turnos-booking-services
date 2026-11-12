# Contratos entre booking y catálogo

## Transporte y autenticación

Booking utiliza `BOOKING_CATALOG_BASE_URL`, configurada como
`http://catalog:8080` en el Compose conjunto. Propaga el JWT del usuario mediante
`Authorization: Bearer <JWT>`; no envía a catálogo el token técnico de cátedra.
Las respuestas son JSON. No existe acceso a tablas ni repositorios del otro
servicio.

El cliente HTTP de booking tiene timeout de conexión de 3 segundos y de lectura
de 5 segundos, sin seguir redirecciones. Un fallo de integración no se convierte
en una lista de turnos libres.

## Operaciones consumidas por booking

| Operación GET | Respuesta 200 | Uso |
| --- | --- | --- |
| `/api/catalog/synchronization/status` | `initialized`, `appliedVersion`, `appliedAt` | Comprobar que existe una copia inicializada |
| `/api/catalog/professionals/{professionalId}/schedules` | Lista de agendas habilitadas | Construir los intervalos de atención |

Estado de sincronización: `initialized` es booleano; `appliedVersion` es un
entero largo nullable y `appliedAt` una fecha/hora ISO-8601 nullable. No representa
la disponibilidad actual del servidor de cátedra.

Cada agenda contiene `id` y `professionalId` (enteros largos), `dayOfWeek`
(enum inglés de MONDAY a SUNDAY), `startTime` y `endTime` (hora local),
`slotDurationMinutes` (entero positivo). Solo se publican agendas cuyo
profesional y categoría también estén habilitados. Una lista vacía no permite
construir nuevos turnos para ese profesional.

Booking comprueba identidad del profesional, intervalo y duración recibidos.
Combina estas agendas con las ocupaciones de cátedra para una fecha concreta;
no conserva una réplica para futuras búsquedas.

## Consultas de catálogo para el cliente

| Operación GET | Campos de cada elemento / parámetros |
| --- | --- |
| `/api/catalog/categories` | `id`, `name`, `description` nullable |
| `/api/catalog/professionals` | `id`, `categoryId`, `firstName`, `lastName` |

La consulta de profesionales requiere `categoryId` positivo. Admite `name`,
`page` (desde 0) y `size` (1 a 100, predeterminado 20). El cuerpo es una lista;
`X-Total-Count` contiene el total filtrado. La búsqueda por nombre es literal
sobre el nombre completo normalizado, ordenada por apellido, nombre e ID.

La issue #53 de catálogo agrega `dayOfWeek` opcional para filtrar por agenda
semanal habilitada, sin duplicar profesionales. Este parámetro requiere desplegar
la versión que incluye esa issue; no garantiza cupos libres en una fecha.

## Errores y comportamiento del consumidor

| Situación | Respuesta / comportamiento |
| --- | --- |
| JWT ausente, inválido o vencido | 401 |
| JWT válido sin ROLE_USER | 403 |
| Identificador no positivo, paginación o día inválido | 400 |
| Categoría sin coincidencias o profesional sin agenda visible | 200 con lista vacía |
| Catálogo no inicializado, respuesta inválida, fallo HTTP o timeout | Booking responde 503 `AVAILABILITY_UNAVAILABLE` al consultar disponibilidad |

No se transmiten cuerpos de error remotos ni credenciales al usuario. El cliente
no debe interpretar 503 como ausencia de ocupaciones. Ante un cambio de DTO,
se deben actualizar y validar ambos extremos antes de desplegarlo.

Los contratos de holds, confirmación, teléfono, resultados y cancelación son
APIs de booking hacia el cliente o integraciones con cátedra; sus detalles están
en las especificaciones por funcionalidad enlazadas desde el README.
