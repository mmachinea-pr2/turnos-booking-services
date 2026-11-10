package ar.edu.programacion2.booking.phone;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "booking.catedra.kafka.enabled", havingValue = "true")
public class PendingBookingEvents {
    private final BookingEventInbox inbox;
    public PendingBookingEvents(BookingEventInbox inbox) { this.inbox = inbox; }
    @Scheduled(fixedDelayString = "${booking.catedra.kafka.pending-delay:30s}")
    public void recover() {
        for (String id : inbox.pending()) {
            try {
                inbox.retry(id);
            } catch (RuntimeException exception) {
                // Preserve the failed row and let other durable events progress. Do not log payloads.
                org.slf4j.LoggerFactory.getLogger(PendingBookingEvents.class)
                    .warn("Pending booking event could not be recovered; review the inbox");
            }
        }
    }
}
