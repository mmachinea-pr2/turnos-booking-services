package ar.edu.programacion2.booking.appointments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record ReconciliationState(String remoteProcessId, Instant updatedAt, long professionalId,
        LocalDate date, LocalTime startTime, Long reservationId, String status) {}
