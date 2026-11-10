package ar.edu.programacion2.booking.phone;

import java.time.Instant;

public record BookingResult(String status, Long reservationId, String eventId, String eventType,
        Instant occurredAt, String requestEventId, String reason, String message, String rejectionEventId) {}
