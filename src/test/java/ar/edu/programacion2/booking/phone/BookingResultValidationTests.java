package ar.edu.programacion2.booking.phone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingResultValidationTests {
    private ObjectNode result(String type) {
        var node=(ObjectNode)JsonMapper.builder().build().readTree(BookingEventTests.JSON);
        node.put("eventType",type).put("groupId","proyecto-pilar-machinea").put("requestEventId","request-1")
            .put("reservationId",8251).put("professionalId",15).put("date","2030-01-02").put("startTime","09:00")
            .put("status",type.equals("AppointmentConfirmed") ? "CONFIRMED" : "CANCELLED").put("reason","HOLD_EXPIRED");
        return node;
    }
    @ParameterizedTest
    @ValueSource(strings={"AppointmentConfirmed","AppointmentCancelled","AdditionalInformationRejected","AppointmentProcessExpired","AppointmentProcessInvalid"})
    void acceptsContractsAndRejectsMissingCorrelationFields(String type) {
        var valid=result(type);
        assertThat(BookingEvent.parse("process-1",valid.toString()).type()).isEqualTo(type);
        for(String field : java.util.List.of("groupId","holdId","requestEventId")) {
            var invalid=valid.deepCopy(); invalid.remove(field);
            assertThatThrownBy(() -> BookingEvent.parse("process-1",invalid.toString())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @ParameterizedTest
    @ValueSource(strings={"0","-1","1.5","9223372036854775808","\"8251\"","null"})
    void rejectsInvalidNumericIdentifiers(String value) {
        for(String field : java.util.List.of("reservationId","professionalId")) {
            var invalid=result("AppointmentConfirmed");
            invalid.set(field,JsonMapper.builder().build().readTree(value));
            assertThatThrownBy(() -> BookingEvent.parse("process-1",invalid.toString())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void validatesDatesTimesStatusAndRejectionDeadline() {
        for(String field : java.util.List.of("date","startTime","status")) {
            var invalid=result("AppointmentConfirmed").put(field,"invalid");
            assertThatThrownBy(() -> BookingEvent.parse("process-1",invalid.toString())).isInstanceOf(IllegalArgumentException.class);
        }
        var fractional=result("AppointmentConfirmed").put("startTime","09:00:00.001");
        assertThatThrownBy(() -> BookingEvent.parse("process-1",fractional.toString())).isInstanceOf(IllegalArgumentException.class);
        var expired=result("AdditionalInformationRejected").put("expiresAt","2030-01-01T09:00:00Z");
        assertThatThrownBy(() -> BookingEvent.parse("process-1",expired.toString())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cancellationAllowsNullAndLongReason() {
        var event=result("AppointmentCancelled").putNull("reason");
        assertThat(BookingEvent.parse("process-1",event.toString()).type()).isEqualTo("AppointmentCancelled");
        event.put("reason","x".repeat(500));
        assertThat(BookingEvent.parse("process-1",event.toString()).type()).isEqualTo("AppointmentCancelled");
    }
    @Test void recoveryContinuesAfterOneInvalidDeferredEvent() {
        var inbox=mock(BookingEventInbox.class);
        when(inbox.pending()).thenReturn(java.util.List.of("invalid","valid"));
        doThrow(new IllegalArgumentException("Invalid event")).when(inbox).retry("invalid");
        new PendingBookingEvents(inbox).recover();
        verify(inbox).retry("valid");
    }
}
