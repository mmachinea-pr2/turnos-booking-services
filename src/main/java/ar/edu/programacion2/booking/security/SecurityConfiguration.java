package ar.edu.programacion2.booking.security;

import java.time.Duration;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new Argon2PasswordEncoder(16, 32, 1, 19456, 2); }

    @Bean JwtEncoder jwtEncoder(JwtSettings settings) {
        return NimbusJwtEncoder.withSecretKey(settings.key()).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean JwtDecoder jwtDecoder(JwtSettings settings) {
        var decoder = NimbusJwtDecoder.withSecretKey(settings.key()).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean validSubject;
            try { validSubject = UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject()); }
            catch (IllegalArgumentException | NullPointerException exception) { validSubject = false; }
            boolean valid = validSubject && jwt.getAudience() != null && jwt.getAudience().contains(settings.audience())
                && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                && !jwt.getIssuedAt().isAfter(java.time.Instant.now())
                && (jwt.getClaim("auth") == null || jwt.getClaim("auth") instanceof String);
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Invalid token claims", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(settings.issuer()), claims));
        return decoder;
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("auth");
        authorities.setAuthorityPrefix("");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http.csrf(csrf -> csrf.disable()) // Bearer header only; no session or cookie authentication.
            .cors(withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(access -> access
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/register", "/api/authenticate").permitAll()
                .anyRequest().hasRole("USER"))
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .build();
    }
}
