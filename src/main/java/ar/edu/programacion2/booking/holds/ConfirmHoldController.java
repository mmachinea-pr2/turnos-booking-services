package ar.edu.programacion2.booking.holds;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class ConfirmHoldController {
    private final ConfirmHoldService service;
    public ConfirmHoldController(ConfirmHoldService service) { this.service = service; }
    @PostMapping("/api/reservation-processes/{id}/confirm")
    public ResponseEntity<ReservationProcess> confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ConfirmHoldRequest request) {
        var process = service.confirm(jwt.getSubject(),id.toString(),request);
        int status = switch(process.status()) {
            case EXPIRED, REJECTED, CANCELLED, INVALID -> 409;
            case CONFIRMED -> 200;
            default -> 202;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .location(URI.create("/api/reservation-processes/"+id)).body(process);
    }
}
