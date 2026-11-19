package ar.edu.programacion2.booking.appointments.application.port.out;

import ar.edu.programacion2.booking.appointments.Appointment;
import ar.edu.programacion2.booking.appointments.domain.ReconciliationDecision;
import ar.edu.programacion2.booking.appointments.domain.ReconciliationState;
import java.time.Instant;
import java.util.function.Function;

public interface ReconciliationStore {
    Snapshot snapshot(String user, String id);

    /** Evaluate against the owned, locked row and persist all effects atomically.
     * The decision callback must not perform network calls. */
    String apply(String user, String id, Appointment remote,
            Function<ReconciliationState, ReconciliationDecision> decide);

    record Snapshot(String remoteProcessId, Instant updatedAt) {}
}
