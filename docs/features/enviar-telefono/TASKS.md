# Tareas — issue #18

- [x] Crear endpoint autenticado de envío y consulta del estado de publicación.
- [x] Validar teléfono, propietario, estado, solicitud actual y vencimiento.
- [x] Persistir AdditionalInformationSubmitted con identidad técnica y requestEventId.
- [x] Agregar migración V5 con outbox y unicidad por solicitud.
- [x] Publicar fuera de la transacción, con confirmación del broker.
- [x] Recuperar envíos tras fallos conservando el mismo eventId y contenido.
- [x] Impedir que trabajadores antiguos sobrescriban otro intento.
- [x] Detener nuevos intentos para solicitudes vencidas o reemplazadas.
- [x] Agregar configuración de entorno y Compose.
- [x] Agregar pruebas y documentación.
- [ ] Verificar el flujo contra cátedra con una reserva real.

El commit, push y PR los realiza la autora. Resultados finales de la reserva corresponden a otra issue.
