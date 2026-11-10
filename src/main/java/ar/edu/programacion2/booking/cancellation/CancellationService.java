package ar.edu.programacion2.booking.cancellation;

import ar.edu.programacion2.booking.users.UserService;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class CancellationService {
    private final UserService users;
    private final CancellationRepository repository;
    private final CancellationGateway gateway;
    private final Clock clock;
    public CancellationService(UserService users, CancellationRepository repository, CancellationGateway gateway, Clock availabilityClock) {
        this.users=users; this.repository=repository; this.gateway=gateway; this.clock=availabilityClock;
    }
    public Cancellation cancel(String user, String id, CancelRequest request) {
        users.account(user);
        var claim=repository.claim(user,id,request,clock.instant());
        if (!claim.send()) return claim.cancellation();
        var outcome=gateway.cancel(claim.cancellation());
        return repository.finish(user,id,outcome,clock.instant());
    }
    public Cancellation get(String user, String id) {
        users.account(user);
        return repository.owned(user,id);
    }
}
