package ar.edu.programacion2.booking.phone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record PhoneDeliverySettings(String groupId, String topic) {
    public PhoneDeliverySettings(
            @Value("${booking.catedra.group-id:proyecto-pilar-machinea}") String groupId,
            @Value("${booking.catedra.kafka.phone-topic:catedra.turnos.telefono.proyecto-pilar-machinea}") String topic) {
        if (!groupId.matches("[a-zA-Z0-9_-]+") || topic.length() > 249
                || !topic.equals("catedra.turnos.telefono." + groupId)) {
            throw new IllegalArgumentException("Phone topic must belong to the configured technical group");
        }
        this.groupId = groupId;
        this.topic = topic;
    }
}
