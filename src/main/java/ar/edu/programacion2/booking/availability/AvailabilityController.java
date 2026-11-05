package ar.edu.programacion2.booking.availability;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class AvailabilityController {
    private final AvailabilityService service;
    public AvailabilityController(AvailabilityService service) { this.service = service; }
    @GetMapping("/api/availability")
    public ResponseEntity<Availability> find(@RequestParam long professionalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.find(professionalId, date, jwt.getTokenValue()));
    }
}
