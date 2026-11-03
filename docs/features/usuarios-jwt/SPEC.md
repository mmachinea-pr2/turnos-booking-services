# Usuarios propios y autenticación JWT

Issue #5. Rama `5-implementar-registro-y-autenticacion-de-ususarios-jwt`. Milestone: Seguridad y usuarios. Booking es dueño de los usuarios finales; la cuenta técnica de cátedra es independiente.

## Contratos HTTP

### POST /api/register

JSON: `login`, `password`, `firstName`, `lastName`, `email`, `imageUrl`, `langKey`.

- login: 3–50 caracteres ASCII, letras, números, `_`, `.`, `@`, `-`. Se guarda en minúsculas.
- password: 4–100 caracteres, sin truncar ni normalizar. Se conserva compatibilidad con el rango del contrato JHipster de referencia.
- email: obligatorio, válido, 5–254 caracteres; se normaliza a minúsculas y se quitan espacios externos.
- firstName/lastName: opcionales, hasta 50 caracteres.
- imageUrl: opcional (omitir o null); hasta 256 caracteres y URL HTTP/HTTPS. No se descarga desde el servidor.
- langKey: opcional, predeterminado `es`; 2–10 letras o guiones.

Estas reglas del usuario final son decisiones documentadas compatibles con los campos solicitados. La referencia de cátedra detalla el alta técnica, que no se reutiliza como endpoint de usuarios propios. El registro responde 201 con el perfil, nunca la contraseña ni su hash. El usuario queda activo inmediatamente. El UUID, ROLE_USER y activación son asignados por el servidor; campos extra enviados para elegir id/autoridades/activación se ignoran.

Login y email tienen restricciones UNIQUE en MySQL; incluso registros concurrentes del mismo valor producen un solo usuario. Duplicados: 409 con `USER_ALREADY_EXISTS`. Solicitudes inválidas: 400 con `INVALID_REQUEST`, sin valores rechazados ni detalles de validación que expongan contraseñas.

### POST /api/authenticate

JSON: `username` (login, no email), `password`, `rememberMe` opcional. No se extiende la vigencia con rememberMe: el TTL es fijo para todos los tokens.

Responde 200 con `{"id_token":"..."}` y cabecera `Authorization: Bearer ...`. Cache-Control no-store. Login inexistente, contraseña incorrecta o usuario inactivo: 401 con el mismo código `INVALID_CREDENTIALS`. Un usuario inexistente también ejecuta una comparación Argon2 contra un hash ficticio para evitar una diferencia trivial de coste.

### GET /api/account

Requiere `Authorization: Bearer <JWT>`. Devuelve exclusivamente el usuario identificado por `sub`, activo y presente en la base. No acepta un identificador de usuario para seleccionar otra cuenta. La propiedad de futuras reservas se implementará usando este identificador autenticado; todavía no existen endpoints de reservas.

Salud GET /actuator/health permanece pública. Los demás endpoints requieren ROLE_USER. Token ausente, alterado, vencido, de otra clave/emisor/audiencia o con claims inválidos: 401; token válido sin ROLE_USER: 403. La aplicación es stateless: no usa cookies, sesión, HTTP Basic ni formulario de login. CSRF se desactiva por ese contrato Bearer exclusivo.

## Contraseñas y persistencia

Argon2id con salt aleatorio de 16 bytes, hash de 32 bytes, memoria 19456 KiB, 2 iteraciones y paralelismo 1. Parámetros basados en [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

Migración real V1 crea `application_user` en la base exclusiva de booking. Se elimina la migración de prueba setup_probe, que nunca se publicó en el JAR ni se ejecutó sobre la base de desarrollo. Las pruebas ahora verifican la migración real. La base de desarrollo anterior solo tenía el historial vacío de Flyway, por lo que V1 puede aplicarse sin borrar datos. No editar migraciones aplicadas para actualizaciones futuras.

## JWT y contrato previsto con catálogo

Spring Security y Nimbus firman/verifican exclusivamente HS256. Claims: sub=UUID estable del usuario, iss=`turnos-booking`, aud=[`turnos-backends`], iat, exp, auth=`ROLE_USER`. Se exige sub válido, iat no futuro, exp posterior a iat y no vencido, emisor y audiencia esperados. Se valida también nbf si existe, sin tolerancia temporal adicional.

BOOKING_JWT_SECRET: Base64 de al menos 32 bytes aleatorios; no es el JWT de cátedra. Sin clave válida el arranque falla. Generación local:

```bash
openssl rand -base64 64 | tr -d '\n'
```

Guardar el resultado en `.env` como BOOKING_JWT_SECRET, nunca en Git. No regenerarlo en cada arranque: los tokens existentes deben seguir verificándose hasta vencer. Cambiar la clave invalida todos los tokens anteriores.

BOOKING_JWT_ISSUER y BOOKING_JWT_AUDIENCE permiten configurar el contrato; BOOKING_JWT_TTL usa 30m por defecto (permitido de 1m a 60m). Sin refresh token ni revocación individual. KMP conserva el token solo durante su sesión y vuelve a iniciar sesión al vencer.

Al integrar catálogo se propagará el JWT del usuario. Catálogo deberá validar misma clave HS256, issuer, audience, vigencia y ROLE_USER; no consultará tablas de booking. Esto supone confianza mutua: ambos backends con la clave pueden firmar tokens. La clave jamás se entrega a KMP. Este PR prepara el contrato, pero no modifica catálogo ni afirma que sus endpoints ya estén protegidos.

## CORS y transporte

BOOKING_CORS_ALLOWED_ORIGINS vacío por defecto: no habilita clientes web de otros orígenes. Para un cliente web autorizado se configura una lista separada por comas de orígenes exactos, por ejemplo `http://localhost:3000`. No admite comodines ni URLs con paths. Permite GET/POST/OPTIONS y cabeceras Authorization/Content-Type, sin cookies. KMP nativo no requiere habilitar CORS.

HTTP queda limitado al entorno local de desarrollo. Para exponer usuarios fuera de ese entorno debe configurarse HTTPS en el punto de entrada; no se implementa TLS en este contenedor. Rate limiting para login/registro queda pendiente para una exposición pública. No se implementan recuperación de contraseña, administración de usuarios ni revocación global inmediata en catálogo.

## Verificación

`./mvnw -B -ntp clean verify` usa MySQL temporal y una clave exclusiva de pruebas. Cubre registro, validación, hash Argon2id, normalización, duplicados concurrentes, campos administrados por servidor, login, cuenta propia, fallos homogéneos, expiración y firma JWT, permisos, CORS, ausencia de sesiones y configuración inválida.

Referencia del mecanismo JWT: [Spring Security Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
