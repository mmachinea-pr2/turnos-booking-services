package ar.edu.programacion2.booking.users;

import ar.edu.programacion2.booking.security.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AccountController {
    private final UserService users;
    private final TokenService tokens;
    public AccountController(UserService users, TokenService tokens) { this.users = users; this.tokens = tokens; }
    @PostMapping("/register")
    public ResponseEntity<UserAccount> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(users.register(request));
    }
    @PostMapping("/authenticate")
    public ResponseEntity<TokenResponse> authenticate(@Valid @RequestBody LoginRequest request) {
        String token = tokens.issue(users.authenticate(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).body(new TokenResponse(token));
    }
    @GetMapping("/account")
    public UserAccount account(@AuthenticationPrincipal Jwt jwt) { return users.account(jwt.getSubject()); }
    public record TokenResponse(String id_token) {
        @Override public String toString() { return "TokenResponse[redacted]"; }
    }
}
