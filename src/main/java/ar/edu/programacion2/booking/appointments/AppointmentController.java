package ar.edu.programacion2.booking.appointments;

import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class AppointmentController {
    private final AppointmentService service;
    public AppointmentController(AppointmentService service) { this.service = service; }
    @GetMapping("/api/appointments")
    public ResponseEntity<AppointmentGateway.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        var result = service.list(jwt.getSubject(),page,size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Total-Count",Long.toString(result.totalElements())).body(result);
    }
    @PostMapping("/api/reservation-processes/{id}/reconcile")
    public ResponseEntity<AppointmentService.Result> reconcile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reconcile(jwt.getSubject(),id.toString()));
    }
}
