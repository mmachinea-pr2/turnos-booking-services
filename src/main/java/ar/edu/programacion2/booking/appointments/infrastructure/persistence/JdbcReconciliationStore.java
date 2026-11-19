package ar.edu.programacion2.booking.appointments.infrastructure.persistence;

import ar.edu.programacion2.booking.appointments.Appointment;
import ar.edu.programacion2.booking.appointments.application.port.out.ReconciliationStore;
import ar.edu.programacion2.booking.appointments.domain.ReconciliationDecision;
import ar.edu.programacion2.booking.appointments.domain.ReconciliationState;
import ar.edu.programacion2.booking.holds.ProcessNotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcReconciliationStore implements ReconciliationStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public JdbcReconciliationStore(JdbcTemplate jdbc, Clock availabilityClock) {
        this.jdbc = jdbc;
        this.clock = availabilityClock;
    }
    @Override
    public Snapshot snapshot(String user, String id) {
        return jdbc.query("SELECT remote_process_id,updated_at FROM reservation_process WHERE user_id = ? AND id = ?",
            (rs,row) -> new Snapshot(rs.getString(1),rs.getTimestamp(2).toInstant()),user,id).stream().findFirst()
            .orElseThrow(ProcessNotFoundException::new);
    }
    @Override
    @Transactional
    public String apply(String user, String id, Appointment remote,
            Function<ReconciliationState, ReconciliationDecision> decide) {
        var current = jdbc.query("SELECT * FROM reservation_process WHERE user_id = ? AND id = ? FOR UPDATE",
            (rs, row) -> new ReconciliationState(rs.getString("remote_process_id"),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("professional_id"),
                rs.getDate("appointment_date").toLocalDate(), rs.getTime("start_time").toLocalTime(),
                rs.getObject("reservation_id", Long.class), rs.getString("status")), user, id)
            .stream().findFirst().orElseThrow(ProcessNotFoundException::new);
        var decision = decide.apply(current);
        if (!decision.changesState()) return decision.outcome();
        jdbc.update("""
            UPDATE reservation_process SET status = ?,reservation_id = ?,error_code = ?,updated_at = ?
            WHERE user_id = ? AND id = ?
            """,decision.nextStatus(),remote.reservationId(),decision.errorCode(),
            Timestamp.from(clock.instant()),user,id);
        if (decision.cancelsReservation()) {
            jdbc.update("""
                UPDATE reservation_process SET cancellation_status = 'CANCELLED',cancellation_error = NULL,cancelled_at = ?
                WHERE user_id = ? AND id = ?
                """,Timestamp.from(remote.cancelledAt()),user,id);
        }
        if (decision.stopsPhoneDelivery()) {
            jdbc.update("""
                UPDATE phone_submission_outbox SET delivery_status = 'STOPPED',lease_token = NULL,lease_until = NULL
                WHERE process_id = ? AND delivery_status IN ('PENDING','SENDING')
                """,id);
        }
        return decision.outcome();
    }
}
