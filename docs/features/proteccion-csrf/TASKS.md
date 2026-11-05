# Issue #8: proteger registro y login con CSRF

Rama: `8-protegwr-registrio-y-login-con-csrf`.
Milestone: Seguridad y usuarios.

- [x] Eliminar las excepciones explícitas de CSRF en registro y login.
- [x] Exponer GET /api/csrf con respuesta no-store.
- [x] Conservar la identidad exclusivamente mediante JWT; la sesión CSRF no autentica.
- [x] Permitir la cabecera CSRF y cookies solo desde orígenes CORS explícitos.
- [x] Documentar el intercambio en Postman y el contrato del cliente.
- [x] Verificar rechazo de tokens ausentes, incorrectos y de otra sesión.
- [x] Verificar suite completa: 47 pruebas sin fallos, errores ni omisiones.
- [ ] Publicar el PR hacia main y confirmar CI/Sonar.

Contrato y pasos: [usuarios y JWT](../usuarios-jwt/SPEC.md#csrf-antes-de-registro-y-login).

Validación local: Maven clean verify y una segunda ejecución verify tras ampliar las comprobaciones CORS, ambas con BUILD SUCCESS. El cambio de rama conserva el mismo código base validado. No se modificaron reglas ni estados de Sonar.
