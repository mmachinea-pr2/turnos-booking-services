package ar.edu.programacion2.booking.phone;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class PhoneSubmissionController {
    private final PhoneSubmissionOutbox outbox;
    public PhoneSubmissionController(PhoneSubmissionOutbox outbox) { this.outbox = outbox; }
    @PostMapping("/api/reservation-processes/{id}/phone")
    public ResponseEntity<PhoneSubmission> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SubmitPhoneRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
            .location(URI.create("/api/reservation-processes/"+id+"/phone-submission"))
            .body(outbox.submit(jwt.getSubject(),id.toString(),request));
    }
    @GetMapping("/api/reservation-processes/{id}/phone-submission")
    public ResponseEntity<PhoneSubmission> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return outbox.latest(jwt.getSubject(),id.toString())
            .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
            .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }
}
