package ar.edu.programacion2.booking;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.context.annotation.Import(BookingApplicationTests.ProtectedWriteController.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingApplicationTests {
    static final String JWT_SECRET = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.8");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("booking.security.allowed-origins", () -> "http://localhost:3000");
        properties.add("booking.security.secret", () -> JWT_SECRET);
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.security.web.FilterChainProxy securityFilters;

    @Test
    void flywayCreatesUserSchema() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'application_user'", Integer.class))
            .isEqualTo(1);
    }

    @LocalServerPort
    int port;

    @Test
    void healthRespondsUpWithoutInternalDetails() throws Exception {
        var response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/configprops", "/actuator/beans"})
    void internalActuatorEndpointsAreNotExposed(String path) throws Exception {
        assertThat(get(path).statusCode()).isEqualTo(401);
    }

    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();

    private java.util.Map<String, Object> registration(String login) {
        var body = new java.util.HashMap<String, Object>();
        body.put("login", login);
        body.put("password", "test-password-123");
        body.put("email", login + "@example.test");
        body.put("firstName", "Pilar");
        body.put("lastName", "Prueba");
        return body;
    }
    private String uniqueLogin() { return "u" + java.util.UUID.randomUUID().toString().replace("-", ""); }
    private String registerUser(String login) throws Exception {
        var response = post("/api/register", registration(login));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }
    private String login(String username) throws Exception {
        var response = post("/api/authenticate", java.util.Map.of("username", username, "password", "test-password-123"));
        assertThat(response.statusCode()).isEqualTo(200);
        String token = json.readTree(response.body()).get("id_token").asText();
        assertThat(response.headers().firstValue("Authorization")).contains("Bearer " + token);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return token;
    }
    @Test
    void registrationUsesArgon2AndServerControlledIdentityThenLoginReturnsOnlyOwnAccount() throws Exception {
        String first = uniqueLogin();
        var body = registration(first.toUpperCase(java.util.Locale.ROOT));
        body.put("id", "chosen-by-client");
        body.put("activated", false);
        body.put("authorities", java.util.List.of("ROLE_ADMIN"));
        var created = post("/api/register", body);
        assertThat(created.statusCode()).isEqualTo(201);
        var user = json.readTree(created.body());
        assertThat(user.get("id").asText()).isNotEqualTo("chosen-by-client");
        assertThat(user.get("activated").asBoolean()).isTrue();
        assertThat(user.get("login").asText()).isEqualTo(first);
        assertThat(user.get("langKey").asText()).isEqualTo("es");
        assertThat(created.body()).doesNotContain("ROLE_ADMIN", "password", "hash");
        String hash = jdbc.queryForObject("SELECT password_hash FROM application_user WHERE login = ?", String.class, first);
        assertThat(hash).startsWith("$argon2id$");
        assertThat(passwords.matches("test-password-123", hash)).isTrue();
        String otherId = registerUser(uniqueLogin());
        var account = request("GET", "/api/account?id=" + otherId, null, login(first));
        assertThat(account.statusCode()).isEqualTo(200);
        assertThat(json.readTree(account.body()).get("id").asText()).isEqualTo(user.get("id").asText());
        assertThat(account.body()).doesNotContain("password", "hash");
    }
    @Test
    void duplicateLoginOrEmailIgnoringCaseReturnsConflict() throws Exception {
        String name = uniqueLogin();
        registerUser(name);
        var duplicateLogin = registration(name.toUpperCase(java.util.Locale.ROOT));
        duplicateLogin.put("email", uniqueLogin() + "@example.test");
        assertThat(post("/api/register", duplicateLogin).statusCode()).isEqualTo(409);
        var duplicateEmail = registration(uniqueLogin());
        duplicateEmail.put("email", name.toUpperCase(java.util.Locale.ROOT) + "@EXAMPLE.TEST");
        assertThat(post("/api/register", duplicateEmail).statusCode()).isEqualTo(409);
    }
    @Test
    void concurrentRegistrationCreatesOnlyOneUser() throws Exception {
        String name = uniqueLogin();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> post("/api/register", registration(name)).statusCode());
            var second = executor.submit(() -> post("/api/register", registration(name)).statusCode());
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_user WHERE login = ?", Integer.class, name)).isEqualTo(1);
    }
    @ParameterizedTest
    @ValueSource(strings = {"login", "password", "email", "firstName", "lastName", "imageUrl", "langKey"})
    void invalidRegistrationDoesNotWriteUser(String field) throws Exception {
        String name = uniqueLogin();
        var body = registration(name);
        body.put(field, switch (field) {
            case "login" -> "invalid login!";
            case "password" -> "123";
            case "email" -> "invalid";
            case "imageUrl" -> "javascript:alert(1)";
            case "langKey" -> "x";
            default -> "a".repeat(51);
        });
        var result = post("/api/register", body);
        assertThat(result.statusCode()).isEqualTo(400);
        assertThat(result.body()).doesNotContain("test-password", "rejectedValue");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_user WHERE login = ?", Integer.class, name)).isZero();
    }
    @Test
    void missingFieldsAndMalformedJsonReturnSafeBadRequest() throws Exception {
        assertThat(post("/api/register", java.util.Map.of()).statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/register", "{invalid", null).statusCode()).isEqualTo(400);
        assertThat(post("/api/authenticate", java.util.Map.of()).statusCode()).isEqualTo(400);
    }
    @Test
    void wrongPasswordUnknownAndInactiveUsersHaveSameError() throws Exception {
        String name = uniqueLogin(); registerUser(name);
        var wrong = post("/api/authenticate", java.util.Map.of("username", name, "password", "incorrect"));
        var unknown = post("/api/authenticate", java.util.Map.of("username", uniqueLogin(), "password", "incorrect"));
        jdbc.update("UPDATE application_user SET activated = false WHERE login = ?", name);
        var inactive = post("/api/authenticate", java.util.Map.of("username", name, "password", "test-password-123"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(inactive.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).isEqualTo(unknown.body()).isEqualTo(inactive.body());
    }
    @Test
    void anonymousAndMalformedTokensAreRejected() throws Exception {
        assertThat(get("/api/account").statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/account", null, "not-a-jwt").statusCode()).isEqualTo(401);
    }
    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "no-expiry", "no-issued", "future", "subject", "signature", "auth-type", "no-audience", "not-before"})
    void invalidSignedTokensAreRejected(String failure) throws Exception {
        var now = java.time.Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
            .issuer(failure.equals("issuer") ? "catedra" : "turnos-booking")
            .audience(java.util.List.of(failure.equals("audience") ? "another-service" : "turnos-backends"))
            .subject(failure.equals("subject") ? "arbitrary" : java.util.UUID.randomUUID().toString())
            .claim("auth", failure.equals("auth-type") ? 123 : "ROLE_USER");
        if (!failure.equals("no-expiry")) claims.expiresAt(failure.equals("expired") ? now.minusSeconds(60) : now.plusSeconds(600));
        if (!failure.equals("no-issued")) claims.issuedAt(failure.equals("future") ? now.plusSeconds(120) : now.minusSeconds(120));
        if (failure.equals("no-audience")) claims.claim("aud", java.util.List.of());
        if (failure.equals("not-before")) claims.notBefore(now.plusSeconds(60));
        var signer = encoder;
        if (failure.equals("signature")) {
            byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 1);
            signer = org.springframework.security.oauth2.jwt.NimbusJwtEncoder.withSecretKey(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                .algorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        }
        String token = signer.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
            org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        assertThat(request("GET", "/api/account", null, token).statusCode()).isEqualTo(401);
    }
    @Test
    void tokenWithoutRequiredRoleIsForbidden() throws Exception {
        var now = java.time.Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("turnos-booking")
            .audience(java.util.List.of("turnos-backends")).subject(java.util.UUID.randomUUID().toString())
            .issuedAt(now).expiresAt(now.plusSeconds(60)).claim("auth", "ROLE_OTHER").build();
        String token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
            org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
        assertThat(request("GET", "/api/account", null, token).statusCode()).isEqualTo(403);
    }
    @Test
    void tokenDoesNotAuthenticateViaCookieAndNoSessionIsCreated() throws Exception {
        String name = uniqueLogin(); registerUser(name);
        String token = login(name);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/account"))
                .header("Cookie", "token=" + token).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        }
    }
    @Test
    void corsAllowsOnlyConfiguredOrigin() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String origin : java.util.List.of("http://localhost:3000", "https://untrusted.example")) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/account"))
                    .header("Origin", origin).header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization, X-CSRF-TOKEN").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(origin.contains("localhost") ? 200 : 403);
                if (origin.contains("localhost")) {
                    assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
                    assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
                    assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("")).contains("X-CSRF-TOKEN");
                }
                else assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            }
        }
    }
    @Test
    void inactiveAccountRejectsPreviouslyIssuedToken() throws Exception {
        String name = uniqueLogin(); registerUser(name);
        String token = login(name);
        jdbc.update("UPDATE application_user SET activated = false WHERE login = ?", name);
        assertThat(request("GET", "/api/account", null, token).statusCode()).isEqualTo(401);
    }
    @org.springframework.web.bind.annotation.RestController
    static class ProtectedWriteController {
        @org.springframework.web.bind.annotation.PostMapping("/api/security-test")
        @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
        void write() { }
    }

    @Test
    void protectedWriteAcceptsBearerWithoutCsrfToken() throws Exception {
        String name = uniqueLogin();
        registerUser(name);
        assertThat(request("POST", "/api/security-test", null, login(name)).statusCode()).isEqualTo(204);
    }

    @Test
    void protectedWriteRejectsMissingCsrfAndCookieToken() throws Exception {
        assertThat(securityFilters.getFilters("/api/security-test"))
            .anyMatch(org.springframework.security.web.csrf.CsrfFilter.class::isInstance);
        assertThat(request("POST", "/api/security-test", null, null).statusCode()).isEqualTo(401);
        String name = uniqueLogin();
        registerUser(name);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/security-test"))
                .header("Cookie", "token=" + login(name))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
    }

    private tools.jackson.databind.JsonNode fetchCsrf(HttpClient client) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/csrf"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return json.readTree(response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/register", "/api/authenticate"})
    void credentialsEndpointsRejectMissingWrongAndOtherSessionCsrf(String path) throws Exception {
        String name = uniqueLogin();
        var body = path.endsWith("register") ? registration(name)
            : java.util.Map.of("username", name, "password", "test-password-123");
        try (var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
             var other = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build()) {
            var csrf = fetchCsrf(client);
            var foreign = fetchCsrf(other);
            for (String token : java.util.List.of("", "invalid", foreign.get("token").asText())) {
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json");
                if (!token.isEmpty()) builder.header(csrf.get("headerName").asText(), token);
                var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.body()).doesNotContain("id_token");
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_user WHERE login = ?", Integer.class, name)).isZero();
            assertThat(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/account"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return request("POST", path, json.writeValueAsString(body), null);
    }
    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).connectTimeout(Duration.ofSeconds(5)).build()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
            if (method.equals("POST") && (path.equals("/api/register") || path.equals("/api/authenticate"))) {
                var csrf = fetchCsrf(client);
                builder.header(csrf.get("headerName").asText(), csrf.get("token").asText());
            }
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (body != null) builder.header("Content-Type", "application/json");
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
