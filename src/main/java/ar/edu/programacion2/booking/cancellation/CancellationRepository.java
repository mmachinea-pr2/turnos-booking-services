package ar.edu.programacion2.booking.cancellation;

import ar.edu.programacion2.booking.holds.HoldConflictException;
import ar.edu.programacion2.booking.holds.ProcessNotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CancellationRepository {
    private static final RowMapper<Cancellation> MAPPER = (rs,row) -> {
        var started = rs.getTimestamp("cancellation_started_at");
        var cancelled = rs.getTimestamp("cancelled_at");
        return new Cancellation(rs.getString("id"),rs.getString("remote_process_id"),rs.getObject("reservation_id",Long.class),
            rs.getString("status"),rs.getString("cancellation_status"),rs.getString("cancellation_reason"),
            started == null ? null : started.toInstant(),cancelled == null ? null : cancelled.toInstant(),rs.getString("cancellation_error"));
    };
    private final JdbcTemplate jdbc;
    public CancellationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Cancellation owned(String user, String id) {
        return jdbc.query("SELECT * FROM reservation_process WHERE user_id = ? AND id = ?",MAPPER,user,id)
            .stream().findFirst().orElseThrow(ProcessNotFoundException::new);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Claim claim(String user, String id, CancelRequest request, Instant now) {
        var current = jdbc.query("SELECT * FROM reservation_process WHERE user_id = ? AND id = ? FOR UPDATE",MAPPER,user,id)
            .stream().findFirst().orElseThrow(ProcessNotFoundException::new);
        if ("CANCELLED".equals(current.processStatus())) return new Claim(current,false);
        if (current.status() != null) {
            if (!Objects.equals(current.reason(),request.reason())) throw new HoldConflictException("CANCELLATION_DATA_CHANGED");
            return new Claim(current,false);
        }
        if (!"CONFIRMED".equals(current.processStatus()) || current.reservationId() == null) {
            throw new HoldConflictException("RESERVATION_NOT_CONFIRMED");
        }
        jdbc.update("""
            UPDATE reservation_process SET cancellation_status = 'REQUESTING', cancellation_reason = ?,
            cancellation_started_at = ?, updated_at = ? WHERE id = ?
            """,request.reason(),Timestamp.from(now),Timestamp.from(now),id);
        return new Claim(owned(user,id),true);
    }
    @Transactional
    public Cancellation finish(String user, String id, CancellationGateway.Outcome outcome, Instant now) {
        jdbc.update("""
            UPDATE reservation_process SET cancellation_status = ?,cancellation_error = ?,cancelled_at = ?,
            status = CASE WHEN ? = 'CANCELLED' THEN 'CANCELLED' ELSE status END,updated_at = ?
            WHERE user_id = ? AND id = ? AND cancellation_status = 'REQUESTING' AND status = 'CONFIRMED'
            """,outcome.status(),outcome.errorCode(),outcome.cancelledAt() == null ? null : Timestamp.from(outcome.cancelledAt()),
            outcome.status(),Timestamp.from(now),user,id);
        return owned(user,id);
    }
    public record Claim(Cancellation cancellation, boolean send) {}
}
