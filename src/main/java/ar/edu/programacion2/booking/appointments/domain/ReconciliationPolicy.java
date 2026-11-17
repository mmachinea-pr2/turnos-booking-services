package ar.edu.programacion2.booking.appointments.domain;

import ar.edu.programacion2.booking.appointments.Appointment;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Pure rules: no framework, database access or network calls. */
public final class ReconciliationPolicy {
    public ReconciliationDecision decide(String user, Instant expectedVersion,
            ReconciliationState current, Appointment remote) {
        if (!Objects.equals(expectedVersion, current.updatedAt())) {
            return ReconciliationDecision.unchanged("CONCURRENT_CHANGE");
        }
        validateIdentity(user, current, remote);
        if (isProtected(current.status(), remote.status())) {
            return ReconciliationDecision.unchanged("UNCHANGED");
        }
        String next = switch (remote.status()) {
            case "CONFIRMED" -> "CONFIRMED";
            case "CANCELLED" -> "CANCELLED";
            case "PHONE_PENDING" -> "WAITING_FOR_PHONE";
            case "FAILED" -> "INVALID";
            default -> throw new InvalidReconciliationException();
        };
        if ("WAITING_FOR_PHONE".equals(next)
                && !Set.of("CONFIRMING", "CONFIRMATION_UNCERTAIN", "WAITING_FOR_PHONE").contains(current.status())) {
            return ReconciliationDecision.unchanged("UNCHANGED");
        }
        return new ReconciliationDecision("RECONCILED", next);
    }

    private boolean isProtected(String current, String remote) {
        return "CANCELLED".equals(current)
            || ("CONFIRMED".equals(current) && !"CANCELLED".equals(remote))
            || ("WAITING_FOR_PHONE".equals(current) && "PHONE_PENDING".equals(remote));
    }

    private void validateIdentity(String user, ReconciliationState current, Appointment remote) {
        if (!user.equals(remote.externalPatientId())
                || !Objects.equals(remote.reservationProcessId(), current.remoteProcessId())
                || remote.professionalId() != current.professionalId()
                || !remote.appointmentDate().equals(current.date())
                || !remote.startTime().equals(current.startTime())
                || (current.reservationId() != null && remote.reservationId() != current.reservationId())) {
            throw new InvalidReconciliationException();
        }
    }
}
