package ar.edu.programacion2.booking.appointments;

import ar.edu.programacion2.booking.holds.*;
import ar.edu.programacion2.booking.users.UserService;
import org.springframework.stereotype.Service;

@Service
public class AppointmentService {
    private final AppointmentGateway gateway;
    private final UserService users;
    private final HoldService holds;
    private final AppointmentReconciliation reconciliation;
    public AppointmentService(AppointmentGateway gateway, UserService users, HoldService holds,
            AppointmentReconciliation reconciliation) {
        this.gateway = gateway;
        this.users = users;
        this.holds = holds;
        this.reconciliation = reconciliation;
    }
    public AppointmentGateway.Page list(String user, int page, int size) {
        users.account(user);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        return gateway.find(user,page,size);
    }
    public Result reconcile(String user, String id) {
        holds.get(user,id);
        var snapshot = reconciliation.snapshot(user,id);
        if (snapshot.remoteProcessId() == null) return result(user,id,"REMOTE_ID_UNKNOWN");
        // Bounded scan without date filters: absence never proves cancellation or expiry.
        // Network calls run outside the short database transaction.
        for (int page = 0; page < 20; page++) {
            var response = gateway.find(user,page,100);
            var matches = response.content().stream().filter(item -> snapshot.remoteProcessId().equals(item.reservationProcessId())).toList();
            if (matches.size() > 1) throw new ar.edu.programacion2.booking.availability.AvailabilityUnavailableException();
            if (!matches.isEmpty()) return result(user,id,reconciliation.apply(user,id,snapshot,matches.getFirst()));
            if ((long)(page + 1) * 100 >= response.totalElements()) return result(user,id,"NOT_FOUND");
        }
        return result(user,id,"SCAN_LIMIT_REACHED");
    }
    private Result result(String user, String id, String outcome) { return new Result(outcome,holds.get(user,id)); }
    public record Result(String outcome, ReservationProcess process) {}
}
