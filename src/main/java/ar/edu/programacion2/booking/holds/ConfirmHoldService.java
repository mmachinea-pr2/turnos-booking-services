package ar.edu.programacion2.booking.holds;

import ar.edu.programacion2.booking.users.UserService;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class ConfirmHoldService {
    private final HoldRepository repository;
    private final HoldGateway gateway;
    private final UserService users;
    private final Clock clock;
    public ConfirmHoldService(HoldRepository repository, HoldGateway gateway, UserService users, Clock availabilityClock) {
        this.repository = repository;
        this.gateway = gateway;
        this.users = users;
        this.clock = availabilityClock;
    }
    public ReservationProcess confirm(String userId, String processId, ConfirmHoldRequest request) {
        users.account(userId);
        var claim = repository.claimConfirmation(userId,processId,request,clock.instant());
        if (!claim.send()) {
            return switch (claim.process().status()) {
                case CONFIRMING, WAITING_FOR_PHONE, CONFIRMATION_UNCERTAIN, EXPIRED, REJECTED -> claim.process();
                default -> throw new HoldConflictException("HOLD_NOT_CONFIRMABLE");
            };
        }
        // The claim is committed before HTTP. An uncertain outcome must never trigger another confirmation.
        var result = gateway.confirm(claim.process(),request,userId);
        return repository.finishConfirmation(userId,processId,result,clock.instant());
    }
}
