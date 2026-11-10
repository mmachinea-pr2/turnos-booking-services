package ar.edu.programacion2.booking.cancellation;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class CancellationController {
    private final CancellationService service;
    public CancellationController(CancellationService service) { this.service=service; }
    @PostMapping("/api/reservation-processes/{id}/cancel")
    public ResponseEntity<Cancellation> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody(required=false) CancelRequest request) {
        var result=service.cancel(jwt.getSubject(),id.toString(),request == null ? new CancelRequest(null) : request);
        int status=switch(result.status()) {
            case "CANCELLED" -> 200;
            case "REJECTED" -> 409;
            default -> 202;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .location(URI.create("/api/reservation-processes/"+id+"/cancellation")).body(result);
    }
    @GetMapping("/api/reservation-processes/{id}/cancellation")
    public ResponseEntity<Cancellation> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var result=service.get(jwt.getSubject(),id.toString());
        if(result.status() == null) return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
