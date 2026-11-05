package ar.edu.programacion2.booking.availability;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityService {
    private final AvailabilitySource source;
    private final Clock clock;
    public AvailabilityService(AvailabilitySource source, Clock availabilityClock) {
        this.source = source;
        this.clock = availabilityClock;
    }

    public Availability find(long professionalId, LocalDate date, String userToken) {
        var now = LocalDateTime.now(clock);
        if (professionalId <= 0 || date == null || date.isBefore(now.toLocalDate())) {
            throw new IllegalArgumentException("Invalid professional or date");
        }
        var schedules = source.schedules(professionalId, userToken);
        var occupancies = source.occupancies(professionalId, date);
        if (schedules == null || occupancies == null) throw new AvailabilityUnavailableException();
        Set<LocalTime> occupied = new HashSet<>();
        for (var occupancy : occupancies) {
            validateOccupancy(occupancy, date);
            // Even an apparently expired hold remains blocked until the source stops reporting it.
            occupied.add(occupancy.startTime());
        }
        Map<LocalTime, LocalTime> slots = new TreeMap<>();
        for (var schedule : schedules) {
            validateSchedule(schedule, professionalId);
            if (schedule.dayOfWeek().equals(date.getDayOfWeek())) addSlots(schedule, slots);
        }
        var available = slots.entrySet().stream()
            .filter(slot -> !occupied.contains(slot.getKey()))
            .filter(slot -> date.atTime(slot.getKey()).isAfter(now))
            .map(slot -> new Availability.Slot(slot.getKey(), slot.getValue())).toList();
        return new Availability(professionalId, date, available);
    }

    private void addSlots(AvailabilitySource.Schedule schedule, Map<LocalTime, LocalTime> slots) {
        int duration = schedule.slotDurationMinutes() * 60;
        int end = schedule.endTime().toSecondOfDay();
        for (int start = schedule.startTime().toSecondOfDay(); start + duration <= end; start += duration) {
            var startTime = LocalTime.ofSecondOfDay(start);
            var endTime = LocalTime.ofSecondOfDay(start + duration);
            var previous = slots.putIfAbsent(startTime, endTime);
            if (previous != null && !previous.equals(endTime)) throw new AvailabilityUnavailableException();
        }
    }

    private void validateSchedule(AvailabilitySource.Schedule s, long professionalId) {
        if (s == null || s.professionalId() == null || s.professionalId() != professionalId
                || s.dayOfWeek() == null || s.startTime() == null || s.endTime() == null
                || !s.endTime().isAfter(s.startTime()) || s.slotDurationMinutes() == null
                || s.slotDurationMinutes() <= 0 || s.slotDurationMinutes() > 1440) {
            throw new AvailabilityUnavailableException();
        }
    }

    private void validateOccupancy(AvailabilitySource.Occupancy o, LocalDate date) {
        if (o == null || !date.equals(o.date()) || o.startTime() == null
                || !("CONFIRMED".equals(o.status()) || "HELD".equals(o.status()))
                || ("HELD".equals(o.status()) && o.expiresAt() == null)) {
            throw new AvailabilityUnavailableException();
        }
    }
}
