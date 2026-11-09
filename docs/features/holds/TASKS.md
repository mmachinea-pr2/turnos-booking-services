# Issue #12

- [x] Validar el slot con catálogo y ocupaciones antes del hold.
- [x] Crear hold por REST con identidad del usuario y token técnico externo.
- [x] Persistir intención, propietario, estado, identificadores y vencimiento.
- [x] Consultar únicamente procesos propios.
- [x] Reconocer reintentos por requestId y serializar intentos concurrentes.
- [x] Conservar resultados inciertos sin repetir operaciones remotas.
- [x] Expirar localmente holds con vencimiento conocido.
- [x] Documentar API y límites de recuperación del contrato.
- [x] Registrar validación final y cobertura.
- [ ] Verificar CI/Sonar del PR.
- [ ] Probar hold real con infraestructura configurada.

La rama incorpora los commits previos de CSRF y disponibilidad hasta `2e6bae8`, ausentes en su base inicial. Si los PR #9 y #11 siguen pendientes, integrarlos antes de este PR. No se generó un nuevo commit al incorporar ese historial.

Validación 07/11/2026: `./mvnw -B -ntp clean verify`, BUILD SUCCESS. 137 pruebas sin fallos, errores ni omisiones. JaCoCo del paquete holds: 119/119 líneas y 70/78 ramas cubiertas. Cliente HTTP compartido: 11/11 líneas cubiertas. La integración usa MySQL temporal y HTTP local; no se solicitaron holds reales a cátedra.
