package ar.edu.programacion2.booking.phone;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingEventInbox {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final BookingOutcomeHandler outcomes;
    public BookingEventInbox(JdbcTemplate jdbc, Clock availabilityClock, BookingOutcomeHandler outcomes) {
        this.outcomes = outcomes;
        this.jdbc = jdbc;
        this.clock = availabilityClock;
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void receive(BookingEvent event) {
        outcomes.validateGroup(event);
        jdbc.update("""
            INSERT INTO booking_event_inbox(event_id,remote_process_id,event_type,payload,processing_status,received_at)
            VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id = event_id
            """,event.eventId(),event.processId(),event.type(),event.payload().toString(),
            (BookingEvent.PHONE_REQUESTED.equals(event.type()) || BookingOutcomeHandler.TYPES.contains(event.type())) ? "PENDING" : "DEFERRED",Timestamp.from(clock.instant()));
        var stored = loadLocked(event.eventId());
        if (!stored.payload().equals(event.payload())) throw new IllegalArgumentException("Conflicting booking event identifier");
        apply(stored);
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void retry(String eventId) { apply(loadLocked(eventId)); }

    public List<String> pending() {
        return jdbc.queryForList("""
            SELECT e.event_id FROM booking_event_inbox e JOIN reservation_process p
            ON p.remote_process_id = e.remote_process_id
            WHERE (e.processing_status = 'PENDING' OR (e.processing_status = 'DEFERRED'
                AND e.event_type IN ('AppointmentConfirmed','AppointmentCancelled','AdditionalInformationRejected','AppointmentProcessExpired','AppointmentProcessInvalid')))
            AND (e.event_type <> 'AdditionalInformationRequested' OR p.status NOT IN ('HELD','CREATING','UNCERTAIN'))
            AND (e.event_type <> 'AdditionalInformationRejected' OR p.phone_request_event_id IS NOT NULL)
            ORDER BY e.received_at, e.event_id LIMIT 100
            """,String.class);
    }
    public Optional<PhoneRequest> phoneRequest(String userId, String id) {
        return jdbc.query("""
            SELECT e.remote_process_id,e.payload FROM reservation_process p
            JOIN booking_event_inbox e ON e.event_id = p.phone_request_event_id
            WHERE p.user_id = ? AND p.id = ?
            """,(rs,row) -> PhoneRequest.from(BookingEvent.parse(rs.getString(1),rs.getString(2))),userId,id)
            .stream().findFirst();
    }
    private BookingEvent loadLocked(String id) {
        return jdbc.queryForObject("SELECT remote_process_id,payload FROM booking_event_inbox WHERE event_id = ? FOR UPDATE",
            (rs,row) -> BookingEvent.parse(rs.getString(1),rs.getString(2)),id);
    }
    private void apply(BookingEvent event) {
        String state = jdbc.queryForObject("SELECT processing_status FROM booking_event_inbox WHERE event_id = ?",String.class,event.eventId());
        if (BookingOutcomeHandler.TYPES.contains(event.type()) && ("PENDING".equals(state) || "DEFERRED".equals(state))) {
            outcomes.validateGroup(event);
            mark(event.eventId(),outcomes.apply(event));
            return;
        }
        if (!"PENDING".equals(state)) return;
        var processes = jdbc.queryForList("SELECT id,hold_id,status,phone_request_event_id,result_occurred_at FROM reservation_process WHERE remote_process_id = ? FOR UPDATE",event.processId());
        if (processes.isEmpty()) return; // Durable inbox permits correlation after a late REST response.
        var process = processes.getFirst();
        if (!event.payload().path("holdId").asString().equals(process.get("hold_id"))) {
            throw new IllegalArgumentException("Booking event hold does not match process");
        }
        String status = (String) process.get("status");
        if (List.of("HELD", "CREATING", "UNCERTAIN").contains(status)) return;
        if (!List.of("CONFIRMING", "CONFIRMATION_UNCERTAIN", "WAITING_FOR_PHONE").contains(status)) {
            mark(event.eventId(), "IGNORED");
            return;
        }
        var resultTime = (Timestamp)process.get("result_occurred_at");
        if (resultTime != null && !BookingEvent.instant(event.payload(),"occurredAt").isAfter(resultTime.toInstant())) {
            mark(event.eventId(), "IGNORED");
            return;
        }
        String previousId = (String) process.get("phone_request_event_id");
        if (previousId != null) {
            var previous = jdbc.queryForObject("SELECT payload FROM booking_event_inbox WHERE event_id = ?",String.class,previousId);
            var old = BookingEvent.parse(event.processId(),previous);
            if (!BookingEvent.instant(event.payload(),"occurredAt").isAfter(BookingEvent.instant(old.payload(),"occurredAt"))) {
                mark(event.eventId(), "IGNORED");
                return;
            }
        }
        jdbc.update("""
            UPDATE reservation_process SET status = 'WAITING_FOR_PHONE', phone_request_event_id = ?,
            expires_at = ?, error_code = NULL, phone_rejection_event_id = NULL, updated_at = ? WHERE id = ?
            """,event.eventId(),Timestamp.from(BookingEvent.instant(event.payload(),"expiresAt")),
            Timestamp.from(clock.instant()),process.get("id"));
        mark(event.eventId(), "APPLIED");
    }
    private void mark(String id, String status) {
        jdbc.update("UPDATE booking_event_inbox SET processing_status = ? WHERE event_id = ?",status,id);
    }
}
