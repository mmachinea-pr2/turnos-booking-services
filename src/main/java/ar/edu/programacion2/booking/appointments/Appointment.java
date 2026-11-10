package ar.edu.programacion2.booking.appointments;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record Appointment(long reservationId, String reservationProcessId, String externalPatientId,
        long professionalId, LocalDate appointmentDate, LocalTime startTime, LocalTime endTime,
        String status, Instant confirmedAt, Instant cancelledAt) {}
