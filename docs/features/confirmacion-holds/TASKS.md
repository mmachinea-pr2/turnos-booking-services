# Issue #14

- [x] Validar cuenta, propietario, datos, estado y vencimiento antes de confirmar.
- [x] Persistir nombres e intención de confirmación mediante V3.
- [x] Confirmar por REST usando exclusivamente identidad local y token técnico.
- [x] Validar identificador remoto, estado y nuevo vencimiento.
- [x] Serializar intentos y evitar reenvíos al repetir.
- [x] Conservar resultados inciertos y rechazos sin retroceder estados resueltos.
- [x] Probar acceso cruzado, expiración, concurrencia y respuesta perdida.
- [x] Documentar contrato y alcance previo a Kafka.
- [x] Registrar resultado final de pruebas y cobertura.
- [ ] Confirmar CI/Sonar del PR hacia main.
- [ ] Verificar contra cátedra con un hold real.

La base original incluía disponibilidad/CSRF, pero no holds. Se incorporó localmente la rama #12 con merge sin commit; el commit del usuario completará esa integración junto con la implementación. Integrar primero el PR de holds si continúa pendiente.

Validación local: `./mvnw -B -ntp clean verify`, BUILD SUCCESS; 173 pruebas sin fallos, errores ni omisiones. Paquete holds: 190/192 líneas y 122/134 ramas cubiertas. Se usaron exclusivamente MySQL temporal y HTTP local. No se publicaron cambios ni se confirmaron reservas reales.
