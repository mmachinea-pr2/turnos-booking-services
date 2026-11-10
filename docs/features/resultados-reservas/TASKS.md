# Tareas — issue #20

- [x] Validar los contratos de confirmación, cancelación, rechazo, expiración e invalidez.
- [x] Persistir resultado y reservationId mediante Flyway V6.
- [x] Correlacionar grupo, proceso, hold, solicitud y datos del turno.
- [x] Evitar efectos duplicados y retrocesos por eventos atrasados.
- [x] Recuperar resultados guardados como DEFERRED.
- [x] Permitir corregir un teléfono rechazado con un evento nuevo y reintentos seguros.
- [x] Detener envíos pendientes al recibir resultados y proteger contra leases antiguos.
- [x] Exponer el resultado únicamente al propietario activo.
- [x] Agregar pruebas y documentación.
- [ ] Verificar el flujo completo contra cátedra.

La reconciliación REST y la solicitud de cancelación se implementarán en otros bloques.
Commit, push y PR a cargo de la autora.
