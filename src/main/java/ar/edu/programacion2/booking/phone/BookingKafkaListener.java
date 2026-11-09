package ar.edu.programacion2.booking.phone;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.MessageListener;

public class BookingKafkaListener implements MessageListener<String, String> {
    private final BookingEventInbox inbox;
    public BookingKafkaListener(BookingEventInbox inbox) { this.inbox = inbox; }
    @Override public void onMessage(ConsumerRecord<String, String> record) {
        inbox.receive(BookingEvent.parse(record.key(), record.value()));
    }
}
