package ar.edu.programacion2.booking.availability;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvailabilityServiceTests {
    final AvailabilitySource source = mock(AvailabilitySource.class);
    final LocalDate date = LocalDate.of(2026, 11, 9);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:15:00Z"), ZoneId.of("America/Argentina/Mendoza"));
    final AvailabilityService service = new AvailabilityService(source, clock);
    AvailabilitySource.Schedule schedule(String start, String end, int minutes) {
        return new AvailabilitySource.Schedule(15L, DayOfWeek.MONDAY, LocalTime.parse(start), LocalTime.parse(end), minutes);
    }
    @Test void combinesSchedulesDeduplicatesAndExcludesPastAndOccupiedSlots() {
        when(source.schedules(15, "user")).thenReturn(List.of(schedule("09:00", "11:10", 30), schedule("09:00", "11:10", 30),
            new AvailabilitySource.Schedule(15L, DayOfWeek.TUESDAY, LocalTime.NOON, LocalTime.of(13,0), 30)));
        when(source.occupancies(15, date)).thenReturn(List.of(
            new AvailabilitySource.Occupancy(date, LocalTime.of(9,30), "CONFIRMED", null),
            new AvailabilitySource.Occupancy(date, LocalTime.of(10,0), "HELD", Instant.EPOCH)));
        var result = service.find(15, date, "user");
        assertThat(result.slots()).containsExactly(new Availability.Slot(LocalTime.of(10,30), LocalTime.of(11,0)));
        verify(source).schedules(15,"user");
    }
    @Test void emptySchedulesAndEmptyOccupanciesAreValid() {
        when(source.schedules(15,"user")).thenReturn(List.of());
        when(source.occupancies(15,date)).thenReturn(List.of());
        assertThat(service.find(15,date,"user").slots()).isEmpty();
    }
    @Test void rejectsInvalidInputBeforeExternalCalls() {
        assertThatThrownBy(() -> service.find(0,date,"user")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.find(15,null,"user")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.find(15,date.minusDays(1),"user")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(source);
    }
    @ParameterizedTest @ValueSource(ints = {0,-1,1441,Integer.MAX_VALUE})
    void invalidDurationsFailClosed(int minutes) {
        when(source.schedules(15,"user")).thenReturn(List.of(schedule("09:00","10:00",minutes)));
        when(source.occupancies(15,date)).thenReturn(List.of());
        assertThatThrownBy(() -> service.find(15,date,"user")).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @Test void invalidSchedulesFailClosed() {
        var bad = Arrays.asList(null,
            new AvailabilitySource.Schedule(null,DayOfWeek.MONDAY,LocalTime.NOON,LocalTime.MAX,30),
            new AvailabilitySource.Schedule(16L,DayOfWeek.MONDAY,LocalTime.NOON,LocalTime.MAX,30),
            new AvailabilitySource.Schedule(15L,null,LocalTime.NOON,LocalTime.MAX,30),
            new AvailabilitySource.Schedule(15L,DayOfWeek.MONDAY,null,LocalTime.MAX,30),
            new AvailabilitySource.Schedule(15L,DayOfWeek.MONDAY,LocalTime.NOON,null,30),
            new AvailabilitySource.Schedule(15L,DayOfWeek.MONDAY,LocalTime.NOON,LocalTime.MAX,null),
            schedule("12:00","11:00",30));
        when(source.occupancies(15,date)).thenReturn(List.of());
        for (var s : bad) {
            when(source.schedules(15,"user")).thenReturn(Collections.singletonList(s));
            assertThatThrownBy(() -> service.find(15,date,"user")).isInstanceOf(AvailabilityUnavailableException.class);
        }
    }
    @Test void invalidOccupanciesFailClosed() {
        var bad = Arrays.asList(null,
            new AvailabilitySource.Occupancy(date.minusDays(1),LocalTime.NOON,"CONFIRMED",null),
            new AvailabilitySource.Occupancy(date,null,"CONFIRMED",null),
            new AvailabilitySource.Occupancy(date,LocalTime.NOON,"UNKNOWN",null),
            new AvailabilitySource.Occupancy(date,LocalTime.NOON,"HELD",null));
        when(source.schedules(15,"user")).thenReturn(List.of());
        for (var o : bad) {
            when(source.occupancies(15,date)).thenReturn(Collections.singletonList(o));
            assertThatThrownBy(() -> service.find(15,date,"user")).isInstanceOf(AvailabilityUnavailableException.class);
        }
    }
    @Test void conflictingDurationsAtSameStartFailClosed() {
        when(source.schedules(15,"user")).thenReturn(List.of(schedule("10:00","11:00",30),schedule("10:00","11:00",60)));
        when(source.occupancies(15,date)).thenReturn(List.of());
        assertThatThrownBy(() -> service.find(15,date,"user")).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @Test void upstreamFailureNeverBecomesFreeSlots() {
        when(source.schedules(15,"user")).thenThrow(new AvailabilityUnavailableException());
        assertThatThrownBy(() -> service.find(15,date,"user")).isInstanceOf(AvailabilityUnavailableException.class);
        verify(source,never()).occupancies(anyLong(),any());
    }
    @Test void missingCollectionsFailClosed() {
        when(source.schedules(15,"user")).thenReturn(null);
        assertThatThrownBy(() -> service.find(15,date,"user")).isInstanceOf(AvailabilityUnavailableException.class);
        when(source.schedules(15,"user")).thenReturn(List.of());
        when(source.occupancies(15,date)).thenReturn(null);
        assertThatThrownBy(() -> service.find(15,date,"user")).isInstanceOf(AvailabilityUnavailableException.class);
    }
}
