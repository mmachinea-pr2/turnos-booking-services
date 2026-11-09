package ar.edu.programacion2.booking.phone;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record BookingEvent(String eventId, String processId, String type, JsonNode payload) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String PHONE_REQUESTED = "AdditionalInformationRequested";

    public static BookingEvent parse(String key, String value) {
        try {
            if (value == null || value.length() > 65536) throw invalid();
            var node = JSON.readTree(value);
            String id = text(node, "eventId", 100);
            String process = text(node, "reservationProcessId", 100);
            String type = text(node, "eventType", 100);
            if (!process.equals(key) || !node.path("schemaVersion").isIntegralNumber()
                    || !java.math.BigInteger.ONE.equals(node.path("schemaVersion").bigIntegerValue())) throw invalid();
            instant(node, "occurredAt");
            if (PHONE_REQUESTED.equals(type)) {
                text(node, "holdId", 100);
                text(node, "message", 2000);
                if (!"PHONE_NUMBER".equals(text(node, "requiredField", 100))
                        || !instant(node, "expiresAt").isAfter(instant(node, "occurredAt"))) throw invalid();
            }
            return new BookingEvent(id, process, type, node);
        } catch (RuntimeException exception) {
            // Never include the raw event or parser exception in logs.
            throw invalid();
        }
    }
    static String text(JsonNode node, String name, int maximum) {
        var field = node.path(name);
        if (!field.isString() || field.asString().isBlank() || field.asString().length() > maximum) throw invalid();
        return field.asString();
    }
    static Instant instant(JsonNode node, String name) {
        return Instant.parse(text(node, name, 50));
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid booking event envelope");
    }
    @Override public String toString() { return "BookingEvent[redacted]"; }
}
