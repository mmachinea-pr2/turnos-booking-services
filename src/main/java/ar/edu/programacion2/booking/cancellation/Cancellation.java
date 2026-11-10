package ar.edu.programacion2.booking.cancellation;

import java.time.Instant;

public record Cancellation(String processId, String reservationProcessId, Long reservationId,
        String processStatus, String status, String reason, Instant startedAt, Instant cancelledAt, String errorCode) {
    @Override public String toString() { return "Cancellation[redacted]"; }
}
