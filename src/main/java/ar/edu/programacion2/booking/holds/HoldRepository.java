package ar.edu.programacion2.booking.holds;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class HoldRepository {
    private final JdbcTemplate jdbc;
    public HoldRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final RowMapper<ReservationProcess> MAPPER = (rs, row) -> {
        var expiry = rs.getTimestamp("expires_at");
        return new ReservationProcess(rs.getString("id"), rs.getString("request_id"), rs.getLong("professional_id"),
            rs.getDate("appointment_date").toLocalDate(), rs.getTime("start_time").toLocalTime(),
            ReservationProcess.Status.valueOf(rs.getString("status")), rs.getString("hold_id"),
            rs.getString("remote_process_id"), expiry == null ? null : expiry.toInstant(), rs.getString("error_code"));
    };
    public Optional<ReservationProcess> byRequest(String userId, String requestId) {
        return jdbc.query("SELECT * FROM reservation_process WHERE user_id = ? AND request_id = ?", MAPPER,
            userId, requestId).stream().findFirst();
    }
    public ReservationProcess owned(String userId, String id, Instant now) {
        jdbc.update("""
            UPDATE reservation_process SET status = 'EXPIRED', updated_at = ?
            WHERE user_id = ? AND id = ? AND status = 'HELD' AND expires_at <= ?
            """, Timestamp.from(now), userId, id, Timestamp.from(now));
        return jdbc.query("SELECT * FROM reservation_process WHERE user_id = ? AND id = ?", MAPPER, userId, id)
            .stream().findFirst().orElseThrow(ProcessNotFoundException::new);
    }
    @Transactional
    public Started start(String userId, CreateHoldRequest request, Instant now) {
        // Serialize creation for a user across application instances; never keep the lock during HTTP.
        var active = jdbc.queryForList("SELECT activated FROM application_user WHERE id = ? FOR UPDATE", Boolean.class, userId);
        if (active.isEmpty() || !Boolean.TRUE.equals(active.getFirst())) throw new BadCredentialsException("Invalid credentials");
        var existing = byRequest(userId, request.requestId().toString());
        if (existing.isPresent()) {
            if (!existing.get().matches(request)) throw new HoldConflictException("REQUEST_ID_REUSED");
            return new Started(existing.get(), false);
        }
        Integer blocking = jdbc.queryForObject("""
            SELECT COUNT(*) FROM reservation_process WHERE user_id = ? AND professional_id = ?
            AND appointment_date = ? AND start_time = ?
            AND (status IN ('CREATING', 'UNCERTAIN', 'CONFIRMING', 'WAITING_FOR_PHONE', 'CONFIRMATION_UNCERTAIN') OR (status = 'HELD' AND expires_at > ?))
            """, Integer.class, userId, request.professionalId(), request.date(), request.startTime(), Timestamp.from(now));
        if (blocking != null && blocking > 0) throw new HoldConflictException("PROCESS_ALREADY_PENDING");
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO reservation_process
            (id,user_id,request_id,professional_id,appointment_date,start_time,status,created_at,updated_at)
            VALUES (?,?,?,?,?,?,'CREATING',?,?)
            """, id, userId, request.requestId().toString(), request.professionalId(), request.date(), request.startTime(),
            Timestamp.from(now), Timestamp.from(now));
        return new Started(owned(userId,id,now), true);
    }
    public ReservationProcess finish(String userId, String id, HoldGateway.Outcome outcome, Instant now) {
        jdbc.update("""
            UPDATE reservation_process SET status = ?, hold_id = ?, remote_process_id = ?, expires_at = ?,
            error_code = ?, updated_at = ? WHERE user_id = ? AND id = ? AND status = 'CREATING'
            """, outcome.status().name(), outcome.holdId(), outcome.processId(),
            outcome.expiresAt() == null ? null : Timestamp.from(outcome.expiresAt()), outcome.errorCode(),
            Timestamp.from(now), userId, id);
        return owned(userId,id,now);
    }
    @Transactional
    public ConfirmationClaim claimConfirmation(String userId, String id, ConfirmHoldRequest request, Instant now) {
        jdbc.query("SELECT * FROM reservation_process WHERE user_id = ? AND id = ? FOR UPDATE", MAPPER,userId,id)
            .stream().findFirst().orElseThrow(ProcessNotFoundException::new);
        var process = owned(userId,id,now);
        var names = jdbc.queryForMap("SELECT patient_first_name, patient_last_name FROM reservation_process WHERE id = ?",id);
        var first = names.get("patient_first_name");
        if (first != null && (!first.equals(request.patientFirstName())
                || !request.patientLastName().equals(names.get("patient_last_name")))) {
            throw new HoldConflictException("CONFIRMATION_DATA_CHANGED");
        }
        if (!ReservationProcess.Status.HELD.equals(process.status())) return new ConfirmationClaim(process,false);
        jdbc.update("""
            UPDATE reservation_process SET status = 'CONFIRMING', patient_first_name = ?, patient_last_name = ?,
            confirmation_started_at = ?, updated_at = ? WHERE user_id = ? AND id = ? AND status = 'HELD'
            """,request.patientFirstName(),request.patientLastName(),Timestamp.from(now),Timestamp.from(now),userId,id);
        return new ConfirmationClaim(owned(userId,id,now),true);
    }
    public ReservationProcess finishConfirmation(String userId, String id, HoldGateway.Outcome outcome, Instant now) {
        jdbc.update("""
            UPDATE reservation_process SET status = ?, expires_at = COALESCE(?, expires_at), error_code = ?, updated_at = ?
            WHERE user_id = ? AND id = ? AND status = 'CONFIRMING'
            """,outcome.status().name(),outcome.expiresAt() == null ? null : Timestamp.from(outcome.expiresAt()),
            outcome.errorCode(),Timestamp.from(now),userId,id);
        return owned(userId,id,now);
    }
    public record ConfirmationClaim(ReservationProcess process, boolean send) {}
    public record Started(ReservationProcess process, boolean created) {}
}
