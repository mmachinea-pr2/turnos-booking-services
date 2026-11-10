package ar.edu.programacion2.booking.holds;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class HoldController {
    private final HoldService service;
    private final ar.edu.programacion2.booking.phone.BookingEventInbox inbox;
    private final ar.edu.programacion2.booking.phone.BookingOutcomeHandler outcomes;
    public HoldController(HoldService service, ar.edu.programacion2.booking.phone.BookingEventInbox inbox,
            ar.edu.programacion2.booking.phone.BookingOutcomeHandler outcomes) {
        this.outcomes = outcomes;
        this.service = service;
        this.inbox = inbox;
    }
    @GetMapping("/api/reservation-processes/{id}/result")
    public ResponseEntity<ar.edu.programacion2.booking.phone.BookingResult> result(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.get(jwt.getSubject(),id.toString());
        return outcomes.result(jwt.getSubject(),id.toString())
            .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
            .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }
    @GetMapping("/api/reservation-processes/{id}/phone-request")
    public ResponseEntity<ar.edu.programacion2.booking.phone.PhoneRequest> phoneRequest(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.get(jwt.getSubject(),id.toString());
        return inbox.phoneRequest(jwt.getSubject(),id.toString())
            .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
            .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }
    @PostMapping("/api/appointment-holds")
    public ResponseEntity<ReservationProcess> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateHoldRequest request) {
        var result = service.create(jwt.getSubject(),jwt.getTokenValue(),request);
        var process = result.process();
        int status = switch (process.status()) {
            case CREATING, UNCERTAIN, CONFIRMING, WAITING_FOR_PHONE, CONFIRMATION_UNCERTAIN -> 202;
            case REJECTED, INVALID -> 409;
            case HELD -> result.created() ? 201 : 200;
            case EXPIRED, CONFIRMED, CANCELLED -> 200;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .location(URI.create("/api/reservation-processes/"+process.id())).body(process);
    }
    @GetMapping("/api/reservation-processes/{id}")
    public ResponseEntity<ReservationProcess> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt.getSubject(),id.toString()));
    }
}
