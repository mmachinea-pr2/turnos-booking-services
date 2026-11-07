package ar.edu.programacion2.booking.holds;

import java.time.*;

public record ReservationProcess(String id, String requestId, long professionalId, LocalDate date,
        LocalTime startTime, Status status, String holdId, String reservationProcessId,
        Instant expiresAt, String errorCode) {
    public enum Status { CREATING, HELD, REJECTED, UNCERTAIN, EXPIRED }
    public boolean matches(CreateHoldRequest request) {
        return professionalId == request.professionalId() && date.equals(request.date())
            && startTime.equals(request.startTime());
    }
}
