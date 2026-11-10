package ar.edu.programacion2.booking.phone;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaPhonePublisherTests {
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String,String> template() { return mock(KafkaTemplate.class); }
    private PhoneSubmissionOutbox.Delivery delivery() {
        return new PhoneSubmissionOutbox.Delivery("event","lease","phone-topic","process","private-payload");
    }
    @Test void waitsForBrokerAcknowledgement() {
        var kafka=template(); when(kafka.send("phone-topic","process","private-payload")).thenReturn(CompletableFuture.completedFuture(null));
        new KafkaPhonePublisher(kafka).publish(delivery());
        verify(kafka).send("phone-topic","process","private-payload");
    }
    @Test void handlesSendFailureWithoutExposingPayload() {
        var kafka=template(); when(kafka.send(anyString(),anyString(),anyString()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("private-payload")));
        assertThatThrownBy(() -> new KafkaPhonePublisher(kafka).publish(delivery()))
            .isInstanceOf(IllegalStateException.class).hasMessage("Phone publication not acknowledged").hasNoCause();
    }
    @Test void handlesSynchronousFailure() {
        var kafka=template(); when(kafka.send(anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> new KafkaPhonePublisher(kafka).publish(delivery())).hasMessage("Phone publication not acknowledged");
    }
    @SuppressWarnings("unchecked")
    @Test void preservesInterruptStatus() throws Exception {
        var kafka=template(); var future=mock(CompletableFuture.class);
        when(future.get(12,TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn((CompletableFuture<SendResult<String,String>>)future);
        try {
            assertThatThrownBy(() -> new KafkaPhonePublisher(kafka).publish(delivery())).hasMessage("Phone publication interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void producerIsDisabledByDefaultAndRejectsBlankBrokerWhenEnabled() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(PhoneProducerConfiguration.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(PhonePublisher.class));
        runner.withPropertyValues("booking.catedra.kafka.enabled=true","booking.catedra.kafka.bootstrap-servers=")
            .run(context -> assertThat(context).hasFailed());
    }
}
