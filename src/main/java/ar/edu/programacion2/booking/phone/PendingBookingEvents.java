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
        for (String id : inbox.pending()) inbox.retry(id);
    }
}
