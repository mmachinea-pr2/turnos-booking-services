package ar.edu.programacion2.booking.phone;

import java.time.Instant;

public record PhoneSubmission(String eventId, String requestEventId, String deliveryStatus,
        int attempts, Instant publishedAt) {}
