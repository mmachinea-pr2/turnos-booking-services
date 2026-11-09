package ar.edu.programacion2.booking.holds;

import ar.edu.programacion2.booking.availability.AvailabilityService;
import ar.edu.programacion2.booking.users.UserService;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class HoldService {
    private final HoldRepository repository;
    private final HoldGateway gateway;
    private final AvailabilityService availability;
    private final UserService users;
    private final Clock clock;
    public HoldService(HoldRepository repository, HoldGateway gateway, AvailabilityService availability,
            UserService users, Clock availabilityClock) {
        this.repository = repository;
        this.gateway = gateway;
        this.availability = availability;
        this.users = users;
        this.clock = availabilityClock;
    }
    public Result create(String userId, String userToken, CreateHoldRequest request) {
        users.account(userId);
        if (request.startTime().getNano() != 0) throw new IllegalArgumentException("Fractional seconds unsupported");
        var existing = repository.byRequest(userId, request.requestId().toString());
        if (existing.isPresent()) {
            if (!existing.get().matches(request)) throw new HoldConflictException("REQUEST_ID_REUSED");
            return new Result(repository.owned(userId,existing.get().id(),clock.instant()),false);
        }
        var available = availability.find(request.professionalId(),request.date(),userToken);
        if (available.slots().stream().noneMatch(slot -> slot.startTime().equals(request.startTime()))) {
            throw new HoldConflictException("SLOT_UNAVAILABLE");
        }
        var started = repository.start(userId,request,clock.instant());
        if (!started.created()) return new Result(repository.owned(userId,started.process().id(),clock.instant()),false);
        // start() has committed before this call. A lost response must not trigger another POST.
        var outcome = gateway.create(request,userId);
        return new Result(repository.finish(userId,started.process().id(),outcome,clock.instant()),true);
    }
    public ReservationProcess get(String userId, String id) {
        users.account(userId);
        return repository.owned(userId,id,clock.instant());
    }
    public record Result(ReservationProcess process, boolean created) {}
}
