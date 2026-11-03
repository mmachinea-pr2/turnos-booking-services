package ar.edu.programacion2.booking.security;

import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

@Component
public class JwtSettings {
    private final SecretKey key;
    private final String issuer;
    private final String audience;
    private final Duration ttl;
    public JwtSettings(@Value("${booking.security.secret:}") String encoded,
            @Value("${booking.security.issuer:turnos-booking}") String issuer,
            @Value("${booking.security.audience:turnos-backends}") String audience,
            @Value("${booking.security.ttl:30m}") String ttl) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 32) throw new IllegalArgumentException();
            this.key = new SecretKeySpec(bytes, "HmacSHA256");
            this.ttl = DurationStyle.detectAndParse(ttl);
            if (this.ttl.compareTo(Duration.ofMinutes(1)) < 0 || this.ttl.compareTo(Duration.ofHours(1)) > 0
                    || issuer.isBlank() || audience.isBlank()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid JWT configuration: require Base64 key of at least 32 bytes, issuer, audience and TTL between 1 and 60 minutes");
        }
        this.issuer = issuer;
        this.audience = audience;
    }
    public SecretKey key() { return key; }
    public String issuer() { return issuer; }
    public String audience() { return audience; }
    public Duration ttl() { return ttl; }
}
