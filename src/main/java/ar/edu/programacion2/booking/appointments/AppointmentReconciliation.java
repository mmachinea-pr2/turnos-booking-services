package ar.edu.programacion2.booking.appointments;

import ar.edu.programacion2.booking.appointments.application.port.out.ReconciliationStore;
import ar.edu.programacion2.booking.appointments.application.port.out.ReconciliationStore.Snapshot;
import ar.edu.programacion2.booking.appointments.domain.InvalidReconciliationException;
import ar.edu.programacion2.booking.appointments.domain.ReconciliationPolicy;
import ar.edu.programacion2.booking.availability.AvailabilityUnavailableException;
import org.springframework.stereotype.Service;

@Service
public class AppointmentReconciliation {
    private final ReconciliationStore store;
    private final ReconciliationPolicy policy;

    public AppointmentReconciliation(ReconciliationStore store, ReconciliationPolicy policy) {
        this.store = store;
        this.policy = policy;
    }
    public Snapshot snapshot(String user, String id) { return store.snapshot(user, id); }

    public String apply(String user, String id, Snapshot snapshot, Appointment remote) {
        try {
            return store.apply(user, id, remote,
                current -> policy.decide(user, snapshot.updatedAt(), current, remote));
        } catch (InvalidReconciliationException exception) {
            throw new AvailabilityUnavailableException();
        }
    }
}
