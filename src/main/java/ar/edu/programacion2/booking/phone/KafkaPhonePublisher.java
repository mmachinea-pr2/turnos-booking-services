package ar.edu.programacion2.booking.phone;

import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaPhonePublisher implements PhonePublisher {
    private final KafkaTemplate<String,String> kafka;
    public KafkaPhonePublisher(KafkaTemplate<String,String> kafka) { this.kafka = kafka; }
    @Override public void publish(PhoneSubmissionOutbox.Delivery delivery) {
        try {
            kafka.send(delivery.topic(),delivery.key(),delivery.payload()).get(12,TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Phone publication interrupted");
        } catch (Exception exception) {
            // A timeout may follow successful delivery. Retrying must preserve the event identity.
            throw new IllegalStateException("Phone publication not acknowledged");
        }
    }
}
