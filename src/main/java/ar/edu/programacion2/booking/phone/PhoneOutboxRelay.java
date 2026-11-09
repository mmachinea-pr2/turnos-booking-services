package ar.edu.programacion2.booking.phone;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "booking.catedra.kafka.enabled", havingValue = "true")
public class PhoneOutboxRelay {
    private final PhoneSubmissionOutbox outbox;
    private final PhonePublisher publisher;
    public PhoneOutboxRelay(PhoneSubmissionOutbox outbox, PhonePublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }
    @Scheduled(fixedDelayString = "${booking.catedra.kafka.phone-delay:2s}")
    public void deliver() {
        for (String id : outbox.due()) {
            if (Thread.currentThread().isInterrupted()) return;
            outbox.claim(id).ifPresent(this::publish);
        }
    }
    private void publish(PhoneSubmissionOutbox.Delivery delivery) {
        try {
            publisher.publish(delivery);
        } catch (RuntimeException exception) {
            outbox.failed(delivery);
            return;
        }
        // A DB failure here leaves SENDING. Lease expiry enables a retry with the SAME eventId.
        outbox.published(delivery);
    }
}
