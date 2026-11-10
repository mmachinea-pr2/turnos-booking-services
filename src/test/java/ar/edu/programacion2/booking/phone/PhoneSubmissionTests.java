package ar.edu.programacion2.booking.phone;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhoneSubmissionTests {
    @ParameterizedTest
    @ValueSource(strings={"1234567","+123456789012345","+54 (9261) 555-12.34"})
    void normalizesValidNumbers(String value) {
        assertThat(new SubmitPhoneRequest("request",value).normalizedPhone()).matches("\\+?[0-9]{7,15}");
        assertThat(new SubmitPhoneRequest("request",value).toString()).isEqualTo("SubmitPhoneRequest[redacted]");
    }
    @ParameterizedTest
    @ValueSource(strings={"123456","1234567890123456","+","abc1234567","123/4567","++1234567","１２３４５６７"})
    void rejectsInvalidNumbers(String value) {
        assertThatThrownBy(() -> new SubmitPhoneRequest("request",value).normalizedPhone()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validatesPhoneTopicAndTechnicalGroup() {
        assertThat(new PhoneDeliverySettings("group-1","catedra.turnos.telefono.group-1").groupId()).isEqualTo("group-1");
        assertThatThrownBy(() -> new PhoneDeliverySettings("","catedra.turnos.telefono.")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhoneDeliverySettings("group","catedra.turnos.telefono.other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhoneDeliverySettings("x".repeat(250),"catedra.turnos.telefono."+"x".repeat(250))).isInstanceOf(IllegalArgumentException.class);
    }
    private PhoneSubmissionOutbox.Delivery delivery() {
        return new PhoneSubmissionOutbox.Delivery("event","lease","topic","process","payload");
    }
    @Test void relayPublishesOutsideClaimAndOnlyAcknowledgesSuccessfulSend() {
        var outbox=mock(PhoneSubmissionOutbox.class); var publisher=mock(PhonePublisher.class); var item=delivery();
        when(outbox.due()).thenReturn(List.of("event","other"));
        when(outbox.claim("event")).thenReturn(Optional.of(item));
        when(outbox.claim("other")).thenReturn(Optional.empty());
        new PhoneOutboxRelay(outbox,publisher).deliver();
        var order=inOrder(outbox,publisher);
        order.verify(outbox).claim("event"); order.verify(publisher).publish(item); order.verify(outbox).published(item);
        verify(outbox,never()).failed(any());
    }
    @Test void relaySchedulesRetryOnPublisherFailureWithoutAcknowledging() {
        var outbox=mock(PhoneSubmissionOutbox.class); var publisher=mock(PhonePublisher.class); var item=delivery();
        when(outbox.due()).thenReturn(List.of("event")); when(outbox.claim("event")).thenReturn(Optional.of(item));
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(item);
        new PhoneOutboxRelay(outbox,publisher).deliver();
        verify(outbox).failed(item); verify(outbox,never()).published(any());
    }
    @Test void relayLeavesLeaseForRecoveryIfDbFailsAfterAcknowledgement() {
        var outbox=mock(PhoneSubmissionOutbox.class); var publisher=mock(PhonePublisher.class); var item=delivery();
        when(outbox.due()).thenReturn(List.of("event")); when(outbox.claim("event")).thenReturn(Optional.of(item));
        doThrow(new IllegalStateException("database unavailable")).when(outbox).published(item);
        assertThatThrownBy(() -> new PhoneOutboxRelay(outbox,publisher).deliver()).isInstanceOf(IllegalStateException.class);
        verify(publisher).publish(item); verify(outbox,never()).failed(any());
    }
    @Test void relayDoesNotClaimWorkWhenInterrupted() {
        var outbox=mock(PhoneSubmissionOutbox.class); var publisher=mock(PhonePublisher.class);
        when(outbox.due()).thenReturn(List.of("event"));
        try {
            Thread.currentThread().interrupt(); new PhoneOutboxRelay(outbox,publisher).deliver();
            verify(outbox,never()).claim(any()); verifyNoInteractions(publisher);
        } finally { Thread.interrupted(); }
    }
}
