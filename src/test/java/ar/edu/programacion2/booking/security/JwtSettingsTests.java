package ar.edu.programacion2.booking.security;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class JwtSettingsTests {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    @ParameterizedTest
    @ValueSource(strings = {"", "not-base64", "c2hvcnQ="})
    void invalidKeysFailWithoutExposingInput(String key) {
        assertThatThrownBy(() -> new JwtSettings(key, "issuer", "audience", "30m"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Invalid JWT configuration").hasNoCause();
    }
    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "59s", "61m", "nonsense"})
    void invalidLifetimeFails(String ttl) {
        assertThatThrownBy(() -> new JwtSettings(KEY, "issuer", "audience", ttl)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void issuerAndAudienceCannotBeBlank() {
        assertThatThrownBy(() -> new JwtSettings(KEY, " ", "audience", "30m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtSettings(KEY, "issuer", "", "30m")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void corsRejectsWildcardsAndUrlsWithPaths() {
        var configuration = new CorsConfiguration();
        for (String origin : new String[]{"*", "https://*.example.test", "https://example.test/path", "https://user@example.test", "https://example.test?q=x"}) {
            assertThatThrownBy(() -> configuration.corsConfigurationSource(origin)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> configuration.corsConfigurationSource("")).doesNotThrowAnyException();
    }
}
