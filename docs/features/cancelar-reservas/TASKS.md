# Tareas — issue #22

- [x] Crear endpoints autenticados para cancelar y consultar el intento.
- [x] Validar propietario, cuenta activa, estado y motivo opcional.
- [x] Persistir el intento antes de llamar a cátedra mediante Flyway V7.
- [x] Validar respuesta e identificadores y conservar resultados inciertos.
- [x] Evitar repetir intentos simultáneos o ya resueltos.
- [x] Coordinar REST con AppointmentCancelled sin regresiones.
- [x] Agregar pruebas y documentación.
- [ ] Verificar contra cátedra con una reserva real.

La issue #24 agrega consulta REST manual para detectar una cancelación completada. No reenvía intentos pendientes/rechazados.
Commit, push y PR a cargo de la autora.
