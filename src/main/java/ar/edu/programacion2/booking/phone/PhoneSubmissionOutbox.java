package ar.edu.programacion2.booking.phone;

import ar.edu.programacion2.booking.holds.HoldConflictException;
import ar.edu.programacion2.booking.holds.ProcessNotFoundException;
import ar.edu.programacion2.booking.users.UserService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PhoneSubmissionOutbox {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RowMapper<PhoneSubmission> MAPPER = (rs,row) -> {
        var published = rs.getTimestamp("published_at");
        return new PhoneSubmission(rs.getString("event_id"),rs.getString("request_event_id"),
            rs.getString("delivery_status"),rs.getInt("attempts"),published == null ? null : published.toInstant());
    };
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final Clock clock;
    private final PhoneDeliverySettings settings;
    public PhoneSubmissionOutbox(JdbcTemplate jdbc, UserService users, Clock availabilityClock, PhoneDeliverySettings settings) {
        this.jdbc = jdbc;
        this.users = users;
        this.clock = availabilityClock;
        this.settings = settings;
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PhoneSubmission submit(String userId, String id, SubmitPhoneRequest request) {
        users.account(userId);
        String phone = request.normalizedPhone();
        var process = jdbc.queryForList("SELECT * FROM reservation_process WHERE id = ? AND user_id = ? FOR UPDATE",id,userId)
            .stream().findFirst().orElseThrow(ProcessNotFoundException::new);
        var existing = jdbc.query("SELECT * FROM phone_submission_outbox WHERE process_id = ? AND request_event_id = ?",MAPPER,id,request.requestEventId());
        if (!existing.isEmpty()) {
            var payload = jdbc.queryForObject("SELECT payload FROM phone_submission_outbox WHERE event_id = ?",String.class,existing.getFirst().eventId());
            if (!phone.equals(JSON.readTree(payload).path("information").path("phoneNumber").asString())) {
                throw new HoldConflictException("PHONE_ALREADY_SUBMITTED");
            }
            return existing.getFirst(); // Replay returns the existing result, even after expiry; never re-enqueues.
        }
        validateRequest(process,request.requestEventId());
        Instant now = clock.instant();
        var expiry = ((Timestamp)process.get("expires_at")).toInstant();
        if (!expiry.isAfter(now)) throw new HoldConflictException("PHONE_REQUEST_EXPIRED");
        String eventId = UUID.randomUUID().toString();
        String payload = JSON.writeValueAsString(Map.of("eventId",eventId,"eventType","AdditionalInformationSubmitted",
            "groupId",settings.groupId(),"reservationProcessId",process.get("remote_process_id"),"holdId",process.get("hold_id"),
            "requestEventId",request.requestEventId(),"information",Map.of("phoneNumber",phone),"occurredAt",now.toString(),"schemaVersion",1));
        jdbc.update("""
            INSERT INTO phone_submission_outbox
            (event_id,process_id,request_event_id,topic,message_key,payload,expires_at,delivery_status,next_attempt_at,created_at)
            VALUES (?,?,?,?,?,?,?,'PENDING',?,?)
            """,eventId,id,request.requestEventId(),settings.topic(),process.get("remote_process_id"),payload,
            Timestamp.from(expiry),Timestamp.from(now),Timestamp.from(now));
        return new PhoneSubmission(eventId,request.requestEventId(),"PENDING",0,null);
    }
    private void validateRequest(Map<String,Object> process, String requestId) {
        if (!"WAITING_FOR_PHONE".equals(process.get("status"))) throw new HoldConflictException("PHONE_NOT_EXPECTED");
        if (!requestId.equals(process.get("phone_request_event_id"))) throw new HoldConflictException("PHONE_REQUEST_CHANGED");
    }
    public Optional<PhoneSubmission> latest(String userId, String id) {
        users.account(userId);
        Integer own = jdbc.queryForObject("SELECT COUNT(*) FROM reservation_process WHERE id = ? AND user_id = ?",Integer.class,id,userId);
        if (own == null || own == 0) throw new ProcessNotFoundException();
        return jdbc.query("""
            SELECT o.* FROM phone_submission_outbox o JOIN reservation_process p ON p.id = o.process_id
            WHERE p.id = ? AND p.user_id = ? AND o.request_event_id = p.phone_request_event_id
            """,MAPPER,id,userId).stream().findFirst();
    }
    public List<String> due() {
        var now = Timestamp.from(clock.instant());
        return jdbc.queryForList("""
            SELECT event_id FROM phone_submission_outbox
            WHERE (delivery_status = 'PENDING' AND next_attempt_at <= ?)
               OR (delivery_status = 'SENDING' AND lease_until <= ?)
            ORDER BY next_attempt_at,event_id LIMIT 20
            """,String.class,now,now);
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Optional<Delivery> claim(String eventId) {
        var ids = jdbc.queryForList("SELECT process_id FROM phone_submission_outbox WHERE event_id = ?",String.class,eventId);
        if (ids.isEmpty()) return Optional.empty();
        // Every sender locks the process first, matching submit() and avoiding lock-order inversions.
        var process = jdbc.queryForMap("SELECT status,phone_request_event_id FROM reservation_process WHERE id = ? FOR UPDATE",ids.getFirst());
        var row = jdbc.queryForMap("SELECT * FROM phone_submission_outbox WHERE event_id = ? FOR UPDATE",eventId);
        Instant now = clock.instant();
        if (!available(row,now)) return Optional.empty();
        if (!"WAITING_FOR_PHONE".equals(process.get("status"))
                || !row.get("request_event_id").equals(process.get("phone_request_event_id"))
                || !((Timestamp)row.get("expires_at")).toInstant().isAfter(now)) {
            jdbc.update("UPDATE phone_submission_outbox SET delivery_status = 'STOPPED', lease_token = NULL, lease_until = NULL WHERE event_id = ?",eventId);
            return Optional.empty();
        }
        String lease = UUID.randomUUID().toString();
        jdbc.update("""
            UPDATE phone_submission_outbox SET delivery_status = 'SENDING', attempts = attempts + 1,
            lease_token = ?, lease_until = ? WHERE event_id = ?
            """,lease,Timestamp.from(now.plusSeconds(60)),eventId);
        return Optional.of(new Delivery(eventId,lease,(String)row.get("topic"),(String)row.get("message_key"),(String)row.get("payload")));
    }
    private boolean available(Map<String,Object> row, Instant now) {
        if ("PENDING".equals(row.get("delivery_status"))) return !((Timestamp)row.get("next_attempt_at")).toInstant().isAfter(now);
        return "SENDING".equals(row.get("delivery_status")) && !((Timestamp)row.get("lease_until")).toInstant().isAfter(now);
    }
    public void published(Delivery delivery) {
        jdbc.update("""
            UPDATE phone_submission_outbox SET delivery_status = 'PUBLISHED', published_at = ?, lease_token = NULL, lease_until = NULL
            WHERE event_id = ? AND delivery_status = 'SENDING' AND lease_token = ?
            """,Timestamp.from(clock.instant()),delivery.eventId(),delivery.leaseToken());
    }
    public void failed(Delivery delivery) {
        jdbc.update("""
            UPDATE phone_submission_outbox SET delivery_status = 'PENDING', next_attempt_at = ?, lease_token = NULL, lease_until = NULL
            WHERE event_id = ? AND delivery_status = 'SENDING' AND lease_token = ?
            """,Timestamp.from(clock.instant().plusSeconds(30)),delivery.eventId(),delivery.leaseToken());
    }
    public record Delivery(String eventId, String leaseToken, String topic, String key, String payload) {
        @Override public String toString() { return "PhoneDelivery[redacted]"; }
    }
}
