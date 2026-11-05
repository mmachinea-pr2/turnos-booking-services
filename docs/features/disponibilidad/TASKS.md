# Issue #10

- [x] Consultar catálogo por REST sin duplicar su persistencia.
- [x] Consultar ocupaciones con la credencial técnica.
- [x] Generar slots, excluir ocupaciones y horarios pasados.
- [x] Proteger GET /api/availability con JWT y ROLE_USER.
- [x] Manejar fallos externos sin informar disponibilidad falsa.
- [x] Configurar URLs, zona y token mediante variables externas.
- [x] Documentar contrato y prueba manual.
- [x] Registrar resultado final de Maven y cobertura.
- [ ] Verificar contra infraestructura real configurada.
- [ ] Confirmar CI/Sonar del PR hacia main.

Validación local: `./mvnw -B -ntp clean verify`: BUILD SUCCESS, 93 pruebas sin fallos, errores ni omisiones. Paquete disponibilidad: 100/100 líneas y 91/92 ramas cubiertas. `docker compose config --quiet` correcto.

La rama incorpora por fast-forward el commit existente `777d4de` de CSRF, que no estaba en su base inicial. Integrar primero el PR de la issue #8 si sigue pendiente. No se creó un commit adicional para esa integración.
