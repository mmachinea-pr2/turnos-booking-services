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
            if (BookingOutcomeHandler.TYPES.contains(type)) validateResult(type,node);
            return new BookingEvent(id, process, type, node);
        } catch (RuntimeException exception) {
            // Never include the raw event or parser exception in logs.
            throw invalid();
        }
    }
    private static void validateResult(String type, JsonNode node) {
        text(node,"groupId",100);
        text(node,"holdId",100);
        text(node,"requestEventId",100);
        switch(type) {
            case BookingOutcomeHandler.CONFIRMED, BookingOutcomeHandler.CANCELLED -> {
                positiveLong(node,"reservationId");
                positiveLong(node,"professionalId");
                java.time.LocalDate.parse(text(node,"date",10));
                if (java.time.LocalTime.parse(text(node,"startTime",18)).getNano() != 0) throw invalid();
                String expected = BookingOutcomeHandler.CONFIRMED.equals(type) ? "CONFIRMED" : "CANCELLED";
                if (!expected.equals(text(node,"status",24))) throw invalid();
                if (BookingOutcomeHandler.CANCELLED.equals(type) && !node.path("reason").isNull()) text(node,"reason",2000);
            }
            case BookingOutcomeHandler.REJECTED -> {
                text(node,"reason",60);
                text(node,"message",2000);
                if (!instant(node,"expiresAt").isAfter(instant(node,"occurredAt"))) throw invalid();
            }
            default -> text(node,"reason",60);
        }
    }
    private static void positiveLong(JsonNode node, String field) {
        var value=node.path(field);
        if (!value.isIntegralNumber() || value.bigIntegerValue().signum() <= 0
                || value.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) throw invalid();
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
