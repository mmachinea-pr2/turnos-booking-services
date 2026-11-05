package ar.edu.programacion2.booking.availability;

import java.time.*;
import java.util.List;

public interface AvailabilitySource {
    List<Schedule> schedules(long professionalId, String userToken);
    List<Occupancy> occupancies(long professionalId, LocalDate date);
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Schedule(Long professionalId, DayOfWeek dayOfWeek, LocalTime startTime,
                    LocalTime endTime, Integer slotDurationMinutes) {}
    record Occupancy(LocalDate date, LocalTime startTime, String status, Instant expiresAt) {}
}
