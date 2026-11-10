package ar.edu.programacion2.booking.phone;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BookingOutcomeHandler {
    static final String CONFIRMED = "AppointmentConfirmed";
    static final String CANCELLED = "AppointmentCancelled";
    static final String REJECTED = "AdditionalInformationRejected";
    static final String EXPIRED = "AppointmentProcessExpired";
    static final String INVALID = "AppointmentProcessInvalid";
    static final Set<String> TYPES = Set.of(CONFIRMED,CANCELLED,REJECTED,EXPIRED,INVALID);
    private final JdbcTemplate jdbc;
    private final PhoneDeliverySettings settings;
    private final Clock clock;
    public BookingOutcomeHandler(JdbcTemplate jdbc, PhoneDeliverySettings settings, Clock availabilityClock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = availabilityClock;
    }
    void validateGroup(BookingEvent event) {
        if (TYPES.contains(event.type()) && !settings.groupId().equals(event.payload().path("groupId").asString())) {
            throw new IllegalArgumentException("Booking result belongs to a different technical group");
        }
    }
    // Invoked inside the inbox transaction; event and process changes commit together.
    String apply(BookingEvent event) {
        var rows = jdbc.queryForList("SELECT * FROM reservation_process WHERE remote_process_id = ? FOR UPDATE",event.processId());
        if (rows.isEmpty()) return "PENDING";
        var process = rows.getFirst();
        if (!event.payload().path("holdId").asString().equals(process.get("hold_id"))) throw mismatch();
        if (CONFIRMED.equals(event.type()) || CANCELLED.equals(event.type())) validateReservation(event,process);
        if (obsolete(event,process)) return "IGNORED";
        if (REJECTED.equals(event.type()) && process.get("phone_request_event_id") == null) return "PENDING";
        String next = switch(event.type()) {
            case CONFIRMED -> "CONFIRMED";
            case CANCELLED -> "CANCELLED";
            case REJECTED -> "WAITING_FOR_PHONE";
            case EXPIRED -> "EXPIRED";
            case INVALID -> "INVALID";
            default -> throw new IllegalArgumentException("Unsupported booking result");
        };
        var payload = event.payload();
        Long reservation = CONFIRMED.equals(event.type()) || CANCELLED.equals(event.type()) ? payload.path("reservationId").longValue() : null;
        Timestamp expiry = REJECTED.equals(event.type()) ? Timestamp.from(BookingEvent.instant(payload,"expiresAt")) : null;
        String reason = !CONFIRMED.equals(event.type()) && payload.path("reason").isString() ? payload.path("reason").asString() : null;
        String message = REJECTED.equals(event.type()) ? payload.path("message").asString() : null;
        jdbc.update("""
            UPDATE reservation_process SET status = ?, reservation_id = COALESCE(?,reservation_id),
            result_event_id = ?,result_event_type = ?,result_occurred_at = ?,result_request_id = ?,
            result_message = ?,result_reason = ?,error_code = ?,phone_rejection_event_id = ?,expires_at = COALESCE(?,expires_at),updated_at = ?
            WHERE id = ?
            """,next,reservation,event.eventId(),event.type(),Timestamp.from(BookingEvent.instant(payload,"occurredAt")),
            payload.path("requestEventId").asString(),message,reason,CONFIRMED.equals(event.type()) || CANCELLED.equals(event.type()) ? null : reason,REJECTED.equals(event.type()) ? event.eventId() : null,
            expiry,Timestamp.from(clock.instant()),process.get("id"));
        if (CANCELLED.equals(event.type())) {
            jdbc.update("""
                UPDATE reservation_process SET cancellation_status = 'CANCELLED', cancellation_error = NULL,
                cancelled_at = ? WHERE id = ?
                """,Timestamp.from(BookingEvent.instant(payload,"occurredAt")),process.get("id"));
        }
        jdbc.update("""
            UPDATE phone_submission_outbox SET delivery_status = 'STOPPED',lease_token = NULL,lease_until = NULL
            WHERE process_id = ? AND delivery_status IN ('PENDING','SENDING')
            """,process.get("id"));
        return "APPLIED";
    }
    private boolean obsolete(BookingEvent event, Map<String,Object> process) {
        var previous = (Timestamp)process.get("result_occurred_at");
        if (previous != null) {
            int order = BookingEvent.instant(event.payload(),"occurredAt").compareTo(previous.toInstant());
            if (order < 0 || (order == 0 && priority(event.type()) <= priority((String)process.get("result_event_type")))) return true;
        }
        if ((process.get("result_event_id") != null || process.get("reservation_id") != null) && terminalBlocks(event.type(),(String)process.get("status"))) return true;
        var request = process.get("phone_request_event_id");
        return request != null && !request.equals(event.payload().path("requestEventId").asString()) && !INVALID.equals(event.type());
    }
    private int priority(String type) {
        return switch(type) {
            case CANCELLED -> 5;
            case CONFIRMED -> 4;
            case EXPIRED -> 3;
            case INVALID -> 2;
            default -> 1;
        };
    }
    private boolean terminalBlocks(String type, String status) {
        return switch(status) {
            case "CANCELLED" -> !CANCELLED.equals(type);
            case "EXPIRED", "INVALID" -> true;
            case "CONFIRMED" -> !CANCELLED.equals(type) && !CONFIRMED.equals(type);
            default -> false;
        };
    }
    private void validateReservation(BookingEvent event, Map<String,Object> process) {
        var payload = event.payload();
        if (((Number)process.get("professional_id")).longValue() != payload.path("professionalId").longValue()
                || !((java.sql.Date)process.get("appointment_date")).toLocalDate().equals(LocalDate.parse(payload.path("date").asString()))
                || !((java.sql.Time)process.get("start_time")).toLocalTime().equals(LocalTime.parse(payload.path("startTime").asString()))) throw mismatch();
        if (process.get("reservation_id") != null
                && ((Number)process.get("reservation_id")).longValue() != payload.path("reservationId").longValue()) throw mismatch();
    }
    private IllegalArgumentException mismatch() { return new IllegalArgumentException("Booking result does not match local process"); }
    public Optional<BookingResult> result(String userId, String id) {
        return jdbc.query("""
            SELECT * FROM reservation_process WHERE user_id = ? AND id = ? AND result_event_id IS NOT NULL
            """,(rs,row) -> new BookingResult(rs.getString("status"),rs.getObject("reservation_id",Long.class),
            rs.getString("result_event_id"),rs.getString("result_event_type"),rs.getTimestamp("result_occurred_at").toInstant(),
            rs.getString("result_request_id"),rs.getString("result_reason"),rs.getString("result_message"),
            rs.getString("phone_rejection_event_id")),userId,id).stream().findFirst();
    }
}
