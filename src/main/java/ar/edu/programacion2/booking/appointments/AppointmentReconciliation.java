package ar.edu.programacion2.booking.appointments;

import ar.edu.programacion2.booking.availability.AvailabilityUnavailableException;
import ar.edu.programacion2.booking.holds.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AppointmentReconciliation {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public AppointmentReconciliation(JdbcTemplate jdbc, Clock availabilityClock) {
        this.jdbc = jdbc;
        this.clock = availabilityClock;
    }
    public Snapshot snapshot(String user, String id) {
        return jdbc.query("SELECT remote_process_id,updated_at FROM reservation_process WHERE user_id = ? AND id = ?",
            (rs,row) -> new Snapshot(rs.getString(1),rs.getTimestamp(2)),user,id).stream().findFirst()
            .orElseThrow(ProcessNotFoundException::new);
    }
    @Transactional
    public String apply(String user, String id, Snapshot snapshot, Appointment remote) {
        var rows = jdbc.queryForList("SELECT * FROM reservation_process WHERE user_id = ? AND id = ? FOR UPDATE",user,id);
        if (rows.isEmpty()) throw new ProcessNotFoundException();
        var current = rows.getFirst();
        if (!Objects.equals(snapshot.updatedAt(),current.get("updated_at"))) return "CONCURRENT_CHANGE";
        if (!user.equals(remote.externalPatientId()) || !remote.reservationProcessId().equals(current.get("remote_process_id"))
                || remote.professionalId() != ((Number)current.get("professional_id")).longValue()
                || !remote.appointmentDate().equals(((java.sql.Date)current.get("appointment_date")).toLocalDate())
                || !remote.startTime().equals(((java.sql.Time)current.get("start_time")).toLocalTime())
                || (current.get("reservation_id") != null && remote.reservationId() != ((Number)current.get("reservation_id")).longValue())) {
            throw new AvailabilityUnavailableException();
        }
        String status = (String)current.get("status");
        if ("CANCELLED".equals(status)) return "UNCHANGED";
        if ("CONFIRMED".equals(status) && !"CANCELLED".equals(remote.status())) return "UNCHANGED";
        if ("WAITING_FOR_PHONE".equals(status) && "PHONE_PENDING".equals(remote.status())) return "UNCHANGED";
        String next = switch(remote.status()) {
            case "CONFIRMED" -> "CONFIRMED";
            case "CANCELLED" -> "CANCELLED";
            case "PHONE_PENDING" -> "WAITING_FOR_PHONE";
            case "FAILED" -> "INVALID";
            default -> throw new AvailabilityUnavailableException();
        };
        if ("WAITING_FOR_PHONE".equals(next) && !java.util.Set.of("CONFIRMING","CONFIRMATION_UNCERTAIN","WAITING_FOR_PHONE").contains(status)) return "UNCHANGED";
        jdbc.update("""
            UPDATE reservation_process SET status = ?,reservation_id = ?,error_code = ?,updated_at = ?
            WHERE user_id = ? AND id = ?
            """,next,remote.reservationId(),"INVALID".equals(next) ? "REMOTE_RESERVATION_FAILED" : null,
            Timestamp.from(clock.instant()),user,id);
        if ("CANCELLED".equals(next)) {
            jdbc.update("""
                UPDATE reservation_process SET cancellation_status = 'CANCELLED',cancellation_error = NULL,cancelled_at = ?
                WHERE user_id = ? AND id = ?
                """,Timestamp.from(remote.cancelledAt()),user,id);
        }
        if (!"WAITING_FOR_PHONE".equals(next)) {
            jdbc.update("""
                UPDATE phone_submission_outbox SET delivery_status = 'STOPPED',lease_token = NULL,lease_until = NULL
                WHERE process_id = ? AND delivery_status IN ('PENDING','SENDING')
                """,id);
        }
        return "RECONCILED";
    }
    public record Snapshot(String remoteProcessId, Timestamp updatedAt) {}
}
