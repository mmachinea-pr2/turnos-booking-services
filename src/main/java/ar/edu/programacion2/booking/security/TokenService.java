package ar.edu.programacion2.booking.security;

import ar.edu.programacion2.booking.users.UserAccount;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final JwtSettings settings;
    public TokenService(JwtEncoder encoder, JwtSettings settings) { this.encoder = encoder; this.settings = settings; }
    public String issue(UserAccount user) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(settings.issuer()).subject(user.id())
            .audience(List.of(settings.audience())).issuedAt(now).expiresAt(now.plus(settings.ttl()))
            .claim("auth", "ROLE_USER").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
