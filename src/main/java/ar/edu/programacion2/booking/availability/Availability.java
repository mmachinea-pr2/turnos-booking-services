package ar.edu.programacion2.booking.availability;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record Availability(long professionalId, LocalDate date, List<Slot> slots) {
    public record Slot(LocalTime startTime, LocalTime endTime) {}
}
