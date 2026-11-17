package ar.edu.programacion2.booking.appointments;

import ar.edu.programacion2.booking.appointments.domain.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ReconciliationPolicyTests {
    private final ReconciliationPolicy policy = new ReconciliationPolicy();
    private final Instant version = Instant.parse("2030-01-01T10:00:00Z");
    private ReconciliationState state(String status) {
        return new ReconciliationState("remote", version, 203, LocalDate.of(2030,1,4), LocalTime.NOON, 42L, status);
    }
    private Appointment remote(String status) {
        return new Appointment(42, "remote", "owner", 203, LocalDate.of(2030,1,4),
            LocalTime.NOON, LocalTime.of(12,20), status, version, version);
    }
    @ParameterizedTest
    @CsvSource({"CANCELLED,CONFIRMED", "CONFIRMED,PHONE_PENDING", "WAITING_FOR_PHONE,PHONE_PENDING", "HELD,PHONE_PENDING"})
    void terminalAndPendingStatesDoNotRegress(String local, String remoteStatus) {
        var decision = policy.decide("owner", version, state(local), remote(remoteStatus));
        assertThat(decision.outcome()).isEqualTo("UNCHANGED");
        assertThat(decision.changesState()).isFalse();
    }
    @ParameterizedTest
    @CsvSource({"CONFIRMED,CONFIRMED", "CANCELLED,CANCELLED", "PHONE_PENDING,WAITING_FOR_PHONE", "FAILED,INVALID"})
    void uncertaintyCanBeResolvedByAuthoritativeState(String remoteStatus, String expected) {
        var decision = policy.decide("owner", version, state("CONFIRMATION_UNCERTAIN"), remote(remoteStatus));
        assertThat(decision.outcome()).isEqualTo("RECONCILED");
        assertThat(decision.nextStatus()).isEqualTo(expected);
        assertThat(decision.cancelsReservation()).isEqualTo(expected.equals("CANCELLED"));
        assertThat(decision.stopsPhoneDelivery()).isEqualTo(!expected.equals("WAITING_FOR_PHONE"));
        assertThat(decision.errorCode()).isEqualTo(expected.equals("INVALID") ? "REMOTE_RESERVATION_FAILED" : null);
    }
    @Test void concurrentChangeWinsEvenOverInconsistentRemoteData() {
        var decision = policy.decide("another-user", version.minusSeconds(1), state("CONFIRMED"), remote("CANCELLED"));
        assertThat(decision.outcome()).isEqualTo("CONCURRENT_CHANGE");
        assertThat(decision.changesState()).isFalse();
    }
    @ParameterizedTest
    @ValueSource(strings={"owner", "process", "professional", "date", "time", "reservation"})
    void rejectsMismatchedIdentityEvenForTerminalState(String field) {
        var r = remote("CANCELLED");
        var bad = new Appointment(field.equals("reservation") ? 99 : r.reservationId(),
            field.equals("process") ? "other" : r.reservationProcessId(),
            field.equals("owner") ? "other" : r.externalPatientId(),
            field.equals("professional") ? 99 : r.professionalId(),
            field.equals("date") ? r.appointmentDate().plusDays(1) : r.appointmentDate(),
            field.equals("time") ? r.startTime().plusMinutes(1) : r.startTime(),
            r.endTime(), r.status(), r.confirmedAt(), r.cancelledAt());
        assertThatThrownBy(() -> policy.decide("owner", version, state("CANCELLED"), bad))
            .isInstanceOf(InvalidReconciliationException.class);
    }
    @Test void acceptsFirstReservationIdentityAndRejectsUnknownRemoteState() {
        var old = state("CONFIRMING");
        var current = new ReconciliationState(old.remoteProcessId(), version, old.professionalId(),
            old.date(), old.startTime(), null, old.status());
        assertThat(policy.decide("owner", version, current, remote("CONFIRMED")).changesState()).isTrue();
        assertThatThrownBy(() -> policy.decide("owner", version, current, remote("UNKNOWN")))
            .isInstanceOf(InvalidReconciliationException.class);
    }
}
