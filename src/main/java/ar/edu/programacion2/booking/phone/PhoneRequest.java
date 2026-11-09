package ar.edu.programacion2.booking.phone;

import java.time.Instant;

public record PhoneRequest(String requestEventId, String requiredField, String message, Instant expiresAt) {
    static PhoneRequest from(BookingEvent event) {
        return new PhoneRequest(event.eventId(), "PHONE_NUMBER", event.payload().path("message").asString(),
            BookingEvent.instant(event.payload(), "expiresAt"));
    }
}
