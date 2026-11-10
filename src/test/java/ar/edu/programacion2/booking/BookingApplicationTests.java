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
            .isEqualTo(6);
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

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    ar.edu.programacion2.booking.availability.AvailabilitySource availabilitySource;

    @Test
    void availabilityRequiresJwtAndReturnsComputedSlotsWithoutCaching() throws Exception {
        assertThat(get("/api/availability?professionalId=15&date=2099-11-09").statusCode()).isEqualTo(401);
        String name = uniqueLogin(); registerUser(name);
        String token = login(name);
        var date = java.time.LocalDate.of(2099,11,9);
        org.mockito.Mockito.when(availabilitySource.schedules(15,token)).thenReturn(java.util.List.of(
            new ar.edu.programacion2.booking.availability.AvailabilitySource.Schedule(15L,date.getDayOfWeek(),
                java.time.LocalTime.of(9,0),java.time.LocalTime.of(10,0),30)));
        org.mockito.Mockito.when(availabilitySource.occupancies(15,date)).thenReturn(java.util.List.of());
        var response = request("GET","/api/availability?professionalId=15&date="+date,null,token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("slots").size()).isEqualTo(2);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void availabilityValidationAndDependencyFailuresHaveSafeErrors() throws Exception {
        String name = uniqueLogin(); registerUser(name);
        String token = login(name);
        for (String query : java.util.List.of("professionalId=0&date=2099-11-09", "professionalId=15&date=2000-01-01",
                "professionalId=abc&date=2099-11-09", "professionalId=15&date=bad", "professionalId=15")) {
            var response = request("GET","/api/availability?"+query,null,token);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("INVALID_AVAILABILITY_REQUEST");
        }
        org.mockito.Mockito.when(availabilitySource.schedules(15,token))
            .thenThrow(new ar.edu.programacion2.booking.availability.AvailabilityUnavailableException());
        var response = request("GET","/api/availability?professionalId=15&date=2099-11-09",null,token);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("AVAILABILITY_UNAVAILABLE").doesNotContain(token,"slots");
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    ar.edu.programacion2.booking.holds.HoldGateway holdGateway;

    private java.util.Map<String,Object> holdBody() {
        return new java.util.HashMap<>(java.util.Map.of("requestId",java.util.UUID.randomUUID().toString(),
            "professionalId",15,"date","2099-11-09","startTime","09:00:00"));
    }
    private String holdUser() throws Exception {
        String name=uniqueLogin(); registerUser(name);
        String token=login(name);
        var date=java.time.LocalDate.of(2099,11,9);
        org.mockito.Mockito.when(availabilitySource.schedules(15,token)).thenReturn(java.util.List.of(
            new ar.edu.programacion2.booking.availability.AvailabilitySource.Schedule(15L,date.getDayOfWeek(),
                java.time.LocalTime.of(9,0),java.time.LocalTime.of(10,0),30)));
        org.mockito.Mockito.when(availabilitySource.occupancies(15,date)).thenReturn(java.util.List.of());
        return token;
    }
    private ar.edu.programacion2.booking.holds.HoldGateway.Outcome held() {
        String suffix=java.util.UUID.randomUUID().toString();
        return new ar.edu.programacion2.booking.holds.HoldGateway.Outcome(
            ar.edu.programacion2.booking.holds.ReservationProcess.Status.HELD,"hold-"+suffix,"process-"+suffix,
            java.time.Instant.now().plusSeconds(120),null);
    }
    private HttpResponse<String> createHold(java.util.Map<String,Object> body,String token) throws Exception {
        return request("POST","/api/appointment-holds",json.writeValueAsString(body),token);
    }

    @Test
    void holdPersistsOwnerAndIdsAndSameRequestIsIdempotent() throws Exception {
        String token=holdUser();
        String owner=json.readTree(request("GET","/api/account",null,token).body()).get("id").asText();
        var body=holdBody(); body.put("externalPatientId","forged"); body.put("userId","forged");
        var remote=held();
        org.mockito.Mockito.when(holdGateway.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(owner)))
            .thenAnswer(call -> {
                assertThat(jdbc.queryForObject("SELECT status FROM reservation_process WHERE user_id = ? AND request_id = ?",
                    String.class,owner,body.get("requestId"))).isEqualTo("CREATING");
                return remote;
            });
        var first=createHold(body,token);
        assertThat(first.statusCode()).isEqualTo(201);
        var process=json.readTree(first.body());
        String id=process.get("id").asText();
        assertThat(process.get("holdId").asText()).isEqualTo(remote.holdId());
        assertThat(process.get("reservationProcessId").asText()).isEqualTo(remote.processId());
        assertThat(first.headers().firstValue("Location")).contains("/api/reservation-processes/"+id);
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var repeat=createHold(body,token);
        assertThat(repeat.statusCode()).isEqualTo(200);
        assertThat(json.readTree(repeat.body()).get("id").asText()).isEqualTo(id);
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(owner));
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).statusCode()).isEqualTo(200);
        String other=holdUser();
        assertThat(request("GET","/api/reservation-processes/"+id,null,other).statusCode()).isEqualTo(404);
        assertThat(request("GET","/api/reservation-processes/"+java.util.UUID.randomUUID(),null,other).statusCode()).isEqualTo(404);
        assertThat(get("/api/reservation-processes/"+id).statusCode()).isEqualTo(401);
        body.put("startTime","09:30:00");
        assertThat(createHold(body,token).body()).contains("REQUEST_ID_REUSED");
    }

    @Test
    void uncertainResultIsPersistedAndNeverResubmittedEvenWithNewRequestId() throws Exception {
        String token=holdUser(); var body=holdBody();
        org.mockito.Mockito.when(holdGateway.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(ar.edu.programacion2.booking.holds.HoldGateway.Outcome.uncertain());
        var response=createHold(body,token);
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("UNCERTAIN","REMOTE_RESULT_UNKNOWN");
        assertThat(createHold(body,token).statusCode()).isEqualTo(202);
        var different=createHold(holdBody(),token);
        assertThat(different.statusCode()).isEqualTo(409);
        assertThat(different.body()).contains("PROCESS_ALREADY_PENDING");
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void definitiveRejectionIsStoredAndReplayed() throws Exception {
        String token=holdUser(); var body=holdBody();
        org.mockito.Mockito.when(holdGateway.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(ar.edu.programacion2.booking.holds.HoldGateway.Outcome.rejected("SLOT_ALREADY_HELD"));
        var response=createHold(body,token);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("REJECTED","SLOT_ALREADY_HELD");
        assertThat(createHold(body,token).statusCode()).isEqualTo(409);
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void expiredHoldRemainsExpiredOnReadAndReplay() throws Exception {
        String token=holdUser(); var body=holdBody();
        org.mockito.Mockito.when(holdGateway.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(held());
        var created=createHold(body,token);
        String id=json.readTree(created.body()).get("id").asText();
        jdbc.update("UPDATE reservation_process SET expires_at = ? WHERE id = ?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),id);
        var expired=request("GET","/api/reservation-processes/"+id,null,token);
        assertThat(expired.statusCode()).isEqualTo(200);
        assertThat(expired.body()).contains("EXPIRED");
        assertThat(createHold(body,token).body()).contains("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_process WHERE id = ?",String.class,id)).isEqualTo("EXPIRED");
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void invalidOrUnavailableSlotDoesNotCallHoldEndpoint() throws Exception {
        String token=holdUser();
        var invalid=holdBody(); invalid.put("startTime","09:15:00");
        var unavailable=createHold(invalid,token);
        assertThat(unavailable.statusCode()).isEqualTo(409);
        assertThat(unavailable.body()).contains("SLOT_UNAVAILABLE");
        for (String field : java.util.List.of("requestId","professionalId","date","startTime")) {
            var body=holdBody(); body.remove(field);
            assertThat(createHold(body,token).statusCode()).isEqualTo(400);
        }
        var fraction=holdBody(); fraction.put("startTime","09:00:00.123");
        assertThat(createHold(fraction,token).statusCode()).isEqualTo(400);
        assertThat(request("POST","/api/appointment-holds","{invalid",token).statusCode()).isEqualTo(400);
        assertThat(request("GET","/api/reservation-processes/not-uuid",null,token).statusCode()).isEqualTo(400);
        org.mockito.Mockito.verifyNoInteractions(holdGateway);
    }

    @Test
    void inactiveUserCannotCreateOrReadProcesses() throws Exception {
        String token=holdUser();
        String id=json.readTree(request("GET","/api/account",null,token).body()).get("id").asText();
        jdbc.update("UPDATE application_user SET activated = false WHERE id = ?",id);
        assertThat(createHold(holdBody(),token).statusCode()).isEqualTo(401);
        assertThat(request("GET","/api/reservation-processes/"+java.util.UUID.randomUUID(),null,token).statusCode()).isEqualTo(401);
        org.mockito.Mockito.verifyNoInteractions(holdGateway);
    }

    @Test
    void parallelSameRequestCallsRemoteOnceAndExposesInFlightProcess() throws Exception {
        String token=holdUser(); var body=holdBody();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(holdGateway.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(call -> { entered.countDown(); assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); return held(); });
        try (var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=executor.submit(() -> createHold(body,token));
            try {
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var repeat=createHold(body,token);
                assertThat(repeat.statusCode()).isEqualTo(202);
                assertThat(repeat.body()).contains("CREATING");
            } finally { release.countDown(); }
            assertThat(first.get().statusCode()).isEqualTo(201);
        }
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void databaseFailureAfterRemoteSuccessLeavesDurableIntentWithoutRetry() throws Exception {
        String first=holdUser(); String second=holdUser(); var body=holdBody();
        org.mockito.Mockito.when(holdGateway.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(held());
        assertThat(createHold(holdBody(),first).statusCode()).isEqualTo(201);
        assertThat(createHold(body,second).statusCode()).isEqualTo(503);
        var replay=createHold(body,second);
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(replay.body()).contains("CREATING");
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(2)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void simultaneousFirstAttemptsSerializeRequestClaimInDatabase(boolean changedSlot) throws Exception {
        String token=holdUser();
        var firstBody=holdBody();
        var secondBody=new java.util.HashMap<>(firstBody);
        if (changedSlot) secondBody.put("startTime","09:30:00");
        var schedules=availabilitySource.schedules(15,token);
        var bothValidating=new java.util.concurrent.CountDownLatch(2);
        org.mockito.Mockito.when(availabilitySource.schedules(15,token)).thenAnswer(call -> {
            bothValidating.countDown();
            assertThat(bothValidating.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return schedules;
        });
        org.mockito.Mockito.when(holdGateway.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(held());
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> createHold(firstBody,token));
            var second=executor.submit(() -> createHold(secondBody,token));
            var results=java.util.List.of(first.get(),second.get());
            assertThat(results.stream().filter(r -> r.statusCode()==201).count()).isEqualTo(1);
            var other=results.stream().filter(r -> r.statusCode()!=201).findFirst().orElseThrow();
            if (changedSlot) {
                assertThat(other.statusCode()).isEqualTo(409);
                assertThat(other.body()).contains("REQUEST_ID_REUSED");
            } else {
                assertThat(other.statusCode()).isIn(200,202);
                assertThat(json.readTree(results.getFirst().body()).get("id").asText())
                    .isEqualTo(json.readTree(results.getLast().body()).get("id").asText());
            }
        }
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    private String createHeldProcess(String token) throws Exception {
        org.mockito.Mockito.when(holdGateway.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(held());
        var response=createHold(holdBody(),token);
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }
    private HttpResponse<String> confirmHold(String id,String token) throws Exception {
        return request("POST","/api/reservation-processes/"+id+"/confirm",
            json.writeValueAsString(java.util.Map.of("patientFirstName"," Ana ","patientLastName"," Perez ","externalPatientId","forged")),token);
    }
    private ar.edu.programacion2.booking.holds.HoldGateway.Outcome waiting() {
        return new ar.edu.programacion2.booking.holds.HoldGateway.Outcome(
            ar.edu.programacion2.booking.holds.ReservationProcess.Status.WAITING_FOR_PHONE,null,null,
            java.time.Instant.now().plusSeconds(300),null);
    }

    @Test
    void confirmationPersistsClaimNamesAndRemoteExpiryAndRepeatsWithoutPosting() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        String owner=json.readTree(request("GET","/api/account",null,token).body()).get("id").asText();
        var outcome=waiting();
        org.mockito.Mockito.when(holdGateway.confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(owner)))
            .thenAnswer(call -> {
                var process=call.getArgument(0,ar.edu.programacion2.booking.holds.ReservationProcess.class);
                var names=call.getArgument(1,ar.edu.programacion2.booking.holds.ConfirmHoldRequest.class);
                assertThat(process.id()).isEqualTo(id);
                assertThat(process.holdId()).startsWith("hold-");
                assertThat(names.patientFirstName()).isEqualTo("Ana");
                assertThat(names.patientLastName()).isEqualTo("Perez");
                assertThat(jdbc.queryForObject("SELECT status FROM reservation_process WHERE id = ?",String.class,id)).isEqualTo("CONFIRMING");
                assertThat(jdbc.queryForObject("SELECT patient_first_name FROM reservation_process WHERE id = ?",String.class,id)).isEqualTo("Ana");
                return outcome;
            });
        var first=confirmHold(id,token);
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(first.body()).contains("WAITING_FOR_PHONE").doesNotContain("forged");
        assertThat(first.headers().firstValue("Location")).contains("/api/reservation-processes/"+id);
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(json.readTree(first.body()).get("expiresAt").asText()).startsWith(outcome.expiresAt().toString().substring(0,19));
        assertThat(confirmHold(id,token).statusCode()).isEqualTo(202);
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).body()).contains("WAITING_FOR_PHONE");
        assertThat(createHold(holdBody(),token).body()).contains("PROCESS_ALREADY_PENDING");
        var changed=request("POST","/api/reservation-processes/"+id+"/confirm",
            "{\"patientFirstName\":\"Otra\",\"patientLastName\":\"Perez\"}",token);
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(changed.body()).contains("CONFIRMATION_DATA_CHANGED");
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(owner));
    }

    @Test
    void confirmationEnforcesOwnershipActivationAndValidNames() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String other=holdUser();
        assertThat(confirmHold(id,other).statusCode()).isEqualTo(404);
        assertThat(confirmHold(java.util.UUID.randomUUID().toString(),token).statusCode()).isEqualTo(404);
        assertThat(confirmHold(id,null).statusCode()).isEqualTo(401);
        for (String body : java.util.List.of("{}","{bad}","{\"patientFirstName\":\"A\",\"patientLastName\":\"Perez\"}",
                "{\"patientFirstName\":\"Ana\",\"patientLastName\":\"   \"}")) {
            assertThat(request("POST","/api/reservation-processes/"+id+"/confirm",body,token).statusCode()).isEqualTo(400);
        }
        var longName=java.util.Map.of("patientFirstName","a".repeat(101),"patientLastName","Perez");
        assertThat(request("POST","/api/reservation-processes/"+id+"/confirm",json.writeValueAsString(longName),token).statusCode()).isEqualTo(400);
        assertThat(confirmHold("bad-id",token).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE application_user SET activated=false WHERE id=(SELECT user_id FROM reservation_process WHERE id=?)",id);
        assertThat(confirmHold(id,token).statusCode()).isEqualTo(401);
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.never()).confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void expiredHoldCannotBeConfirmedAndExpiryIsCommitted() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        jdbc.update("UPDATE reservation_process SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),id);
        var result=confirmHold(id,token);
        assertThat(result.statusCode()).isEqualTo(409);
        assertThat(result.body()).contains("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_process WHERE id=?",String.class,id)).isEqualTo("EXPIRED");
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.never()).confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @ParameterizedTest
    @ValueSource(strings={"CREATING","UNCERTAIN"})
    void holdWithoutKnownSuccessfulCreationIsNotConfirmable(String status) throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        jdbc.update("UPDATE reservation_process SET status=? WHERE id=?",status,id);
        var result=confirmHold(id,token);
        assertThat(result.statusCode()).isEqualTo(409);
        assertThat(result.body()).contains("HOLD_NOT_CONFIRMABLE");
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.never()).confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void uncertainConfirmationPreservesIdentifiersAndIsNotRetriedOrExpiredByOldDeadline() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        org.mockito.Mockito.when(holdGateway.confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(ar.edu.programacion2.booking.holds.HoldGateway.Outcome.confirmationUncertain());
        var first=confirmHold(id,token);
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(first.body()).contains("CONFIRMATION_UNCERTAIN","CONFIRMATION_RESULT_UNKNOWN","hold-","process-");
        jdbc.update("UPDATE reservation_process SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),id);
        assertThat(confirmHold(id,token).body()).contains("CONFIRMATION_UNCERTAIN");
        assertThat(createHold(holdBody(),token).body()).contains("PROCESS_ALREADY_PENDING");
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void confirmationRejectionIsPersistedAndNeverResent() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        org.mockito.Mockito.when(holdGateway.confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(ar.edu.programacion2.booking.holds.HoldGateway.Outcome.rejected("INVALID_SLOT"));
        assertThat(confirmHold(id,token).statusCode()).isEqualTo(409);
        assertThat(confirmHold(id,token).body()).contains("REJECTED","INVALID_SLOT");
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void simultaneousConfirmationsSendOnceAndLateResponseDoesNotOverwriteAnotherState() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(holdGateway.confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(call -> { entered.countDown(); assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); return waiting(); });
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=executor.submit(() -> confirmHold(id,token));
            try {
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var repeated=confirmHold(id,token);
                assertThat(repeated.statusCode()).isEqualTo(202);
                assertThat(repeated.body()).contains("CONFIRMING");
                jdbc.update("UPDATE reservation_process SET status='REJECTED',error_code='TERMINAL_EVENT' WHERE id=?",id);
            } finally { release.countDown(); }
            assertThat(first.get().statusCode()).isEqualTo(409);
        }
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).body()).contains("REJECTED","TERMINAL_EVENT");
        org.mockito.Mockito.verify(holdGateway,org.mockito.Mockito.times(1)).confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
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

    @Autowired ar.edu.programacion2.booking.phone.BookingEventInbox bookingInbox;

    private ar.edu.programacion2.booking.phone.BookingEvent phoneEvent(String id, String eventId, String occurred) {
        String remote = jdbc.queryForObject("SELECT remote_process_id FROM reservation_process WHERE id = ?",String.class,id);
        String hold = jdbc.queryForObject("SELECT hold_id FROM reservation_process WHERE id = ?",String.class,id);
        return ar.edu.programacion2.booking.phone.BookingEvent.parse(remote,json.writeValueAsString(java.util.Map.of(
            "eventId",eventId,"reservationProcessId",remote,"holdId",hold,"eventType","AdditionalInformationRequested",
            "requiredField","PHONE_NUMBER","message","Ingrese un telefono","schemaVersion",1,
            "occurredAt",occurred,"expiresAt","2030-01-01T11:00:00Z")));
    }
    private String inboxState(String eventId) {
        return jdbc.queryForObject("SELECT processing_status FROM booking_event_inbox WHERE event_id = ?",String.class,eventId);
    }
    @Test
    void phoneEventBeforeRestResponsePersistsRequestAndDoesNotRegress() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        var event=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:00:00Z");
        org.mockito.Mockito.when(holdGateway.confirm(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(call -> { bookingInbox.receive(event); return waiting(); });
        var response=confirmHold(id,token);
        assertThat(response.body()).contains("WAITING_FOR_PHONE","2030-01-01T11:00:00Z");
        bookingInbox.receive(event);
        assertThat(inboxState(event.eventId())).isEqualTo("APPLIED");
        String path="/api/reservation-processes/"+id+"/phone-request";
        var phone=request("GET",path,null,token);
        assertThat(phone.statusCode()).isEqualTo(200);
        assertThat(phone.body()).contains(event.eventId(),"PHONE_NUMBER","Ingrese un telefono");
        assertThat(phone.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(request("GET",path,null,null).statusCode()).isEqualTo(401);
        assertThat(request("GET",path,null,holdUser()).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE application_user SET activated = FALSE WHERE id = (SELECT user_id FROM reservation_process WHERE id = ?)",id);
        assertThat(request("GET",path,null,token).statusCode()).isEqualTo(401);
    }
    @Test
    void pendingPhoneEventRecoversAfterProcessBecomesCorrelatable() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        assertThat(request("GET","/api/reservation-processes/"+id+"/phone-request",null,token).statusCode()).isEqualTo(204);
        var event=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:00:00Z");
        jdbc.update("UPDATE reservation_process SET remote_process_id = NULL WHERE id = ?",id);
        bookingInbox.receive(event);
        assertThat(inboxState(event.eventId())).isEqualTo("PENDING");
        assertThat(bookingInbox.pending()).doesNotContain(event.eventId());
        jdbc.update("UPDATE reservation_process SET remote_process_id = ? WHERE id = ?",event.processId(),id);
        bookingInbox.retry(event.eventId());
        assertThat(inboxState(event.eventId())).isEqualTo("PENDING");
        jdbc.update("UPDATE reservation_process SET status = 'CONFIRMATION_UNCERTAIN' WHERE id = ?",id);
        assertThat(bookingInbox.pending()).contains(event.eventId());
        new ar.edu.programacion2.booking.phone.PendingBookingEvents(bookingInbox).recover();
        assertThat(inboxState(event.eventId())).isEqualTo("APPLIED");
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).body()).contains("WAITING_FOR_PHONE");
    }
    @Test
    void outOfOrderPhoneRequestsAndTerminalStatesNeverRegress() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        jdbc.update("UPDATE reservation_process SET status = 'WAITING_FOR_PHONE' WHERE id = ?",id);
        var first=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:00:00Z");
        var newer=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:02:00Z");
        var older=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:01:00Z");
        bookingInbox.receive(first); bookingInbox.receive(newer); bookingInbox.receive(older);
        assertThat(inboxState(older.eventId())).isEqualTo("IGNORED");
        assertThat(bookingInbox.phoneRequest(jdbc.queryForObject("SELECT user_id FROM reservation_process WHERE id = ?",String.class,id),id).orElseThrow().requestEventId()).isEqualTo(newer.eventId());
        jdbc.update("UPDATE reservation_process SET status = 'REJECTED' WHERE id = ?",id);
        var last=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:03:00Z");
        bookingInbox.receive(last);
        assertThat(inboxState(last.eventId())).isEqualTo("IGNORED");
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).body()).contains("REJECTED");
    }
    @Test
    void conflictingEventOrHoldRollsBackAndOtherTypesAreDurablyDeferred() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        jdbc.update("UPDATE reservation_process SET status = 'CONFIRMING' WHERE id = ?",id);
        var event=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:00:00Z");
        var bad=ar.edu.programacion2.booking.phone.BookingEvent.parse(event.processId(),event.payload().toString().replace(event.payload().path("holdId").asString(),"wrong-hold"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bookingInbox.receive(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_event_inbox WHERE event_id = ?",Integer.class,event.eventId())).isZero();
        bookingInbox.receive(event);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bookingInbox.receive(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(inboxState(event.eventId())).isEqualTo("APPLIED");
        var deferred=ar.edu.programacion2.booking.phone.BookingEvent.parse(event.processId(),event.payload().toString()
            .replace(event.eventId(),java.util.UUID.randomUUID().toString()).replace("AdditionalInformationRequested","FutureBookingEvent"));
        bookingInbox.receive(deferred); bookingInbox.receive(deferred);
        assertThat(inboxState(deferred.eventId())).isEqualTo("DEFERRED");
    }
    @Test
    void simultaneousDuplicatePhoneEventsHaveOneDurableEffect() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        jdbc.update("UPDATE reservation_process SET status = 'CONFIRMING' WHERE id = ?",id);
        var event=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:00:00Z");
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> bookingInbox.receive(event));
            var second=executor.submit(() -> bookingInbox.receive(event));
            first.get(10,java.util.concurrent.TimeUnit.SECONDS); second.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(inboxState(event.eventId())).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_event_inbox WHERE event_id = ?",Integer.class,event.eventId())).isEqualTo(1);
    }

    @Autowired ar.edu.programacion2.booking.phone.PhoneSubmissionOutbox phoneOutbox;

    private String requestedPhone(String id) {
        jdbc.update("UPDATE reservation_process SET status = 'CONFIRMING' WHERE id = ?",id);
        var event=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:00:00Z");
        bookingInbox.receive(event);
        return event.eventId();
    }
    private HttpResponse<String> submitPhone(String token,String id,String requestId,String phone) throws Exception {
        return request("POST","/api/reservation-processes/"+id+"/phone",json.writeValueAsString(java.util.Map.of(
            "requestEventId",requestId,"phoneNumber",phone,"externalPatientId","forged","groupId","forged")),token);
    }
    private String submissionEvent(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).path("eventId").asString();
    }
    @Test
    void phoneSubmissionPersistsContractBeforePublicationAndReplaysNormalizedNumber() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String requestId=requestedPhone(id);
        assertThat(request("GET","/api/reservation-processes/"+id+"/phone-submission",null,token).statusCode()).isEqualTo(204);
        var response=submitPhone(token,id,requestId,"+54 (9261) 555-12.34");
        String eventId=submissionEvent(response);
        assertThat(response.body()).contains("PENDING").doesNotContain("555","forged");
        assertThat(response.headers().firstValue("Location")).contains("/api/reservation-processes/"+id+"/phone-submission");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM phone_submission_outbox WHERE event_id = ?",String.class,eventId));
        assertThat(payload.path("eventType").asString()).isEqualTo("AdditionalInformationSubmitted");
        assertThat(payload.path("eventId").asString()).isEqualTo(eventId);
        assertThat(payload.path("requestEventId").asString()).isEqualTo(requestId);
        assertThat(payload.path("groupId").asString()).isEqualTo("proyecto-pilar-machinea");
        assertThat(payload.path("information").path("phoneNumber").asString()).isEqualTo("+5492615551234");
        assertThat(payload.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("holdId").asString()).isEqualTo(jdbc.queryForObject("SELECT hold_id FROM reservation_process WHERE id = ?",String.class,id));
        assertThat(payload.path("reservationProcessId").asString()).isEqualTo(jdbc.queryForObject("SELECT message_key FROM phone_submission_outbox WHERE event_id = ?",String.class,eventId));
        assertThat(java.time.Instant.parse(payload.path("occurredAt").asString())).isBeforeOrEqualTo(java.time.Instant.now());
        assertThat(submissionEvent(submitPhone(token,id,requestId,"+5492615551234"))).isEqualTo(eventId);
        assertThat(submitPhone(token,id,requestId,"+5492615559999").statusCode()).isEqualTo(409);
        var current=request("GET","/api/reservation-processes/"+id+"/phone-submission",null,token);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(current.body()).contains(eventId,"PENDING").doesNotContain("555");
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).body()).contains("WAITING_FOR_PHONE");
    }
    @Test
    void phoneSubmissionEnforcesOwnerActivationAndInputValidation() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String requestId=requestedPhone(id); String other=holdUser();
        assertThat(submitPhone(null,id,requestId,"+5492615551234").statusCode()).isEqualTo(401);
        assertThat(submitPhone(other,id,requestId,"+5492615551234").statusCode()).isEqualTo(404);
        assertThat(submitPhone(token,java.util.UUID.randomUUID().toString(),requestId,"+5492615551234").statusCode()).isEqualTo(404);
        assertThat(request("GET","/api/reservation-processes/"+id+"/phone-submission",null,other).statusCode()).isEqualTo(404);
        assertThat(request("GET","/api/reservation-processes/"+id+"/phone-submission",null,null).statusCode()).isEqualTo(401);
        for(String body : java.util.List.of("{}","null","{bad}","{\"phoneNumber\":\"1234567\"}","{\"requestEventId\":\"x\"}")) {
            assertThat(request("POST","/api/reservation-processes/"+id+"/phone",body,token).statusCode()).isEqualTo(400);
        }
        for(String phone : java.util.List.of("", "123", "++12345678", "1234567890123456", "abc1234567", "x".repeat(101))) {
            assertThat(submitPhone(token,id,requestId,phone).statusCode()).isEqualTo(400);
        }
        assertThat(submitPhone(token,"bad-id",requestId,"+5492615551234").statusCode()).isEqualTo(400);
        assertThat(submitPhone(token,id,"x".repeat(101),"+5492615551234").statusCode()).isEqualTo(400);
        jdbc.update("UPDATE application_user SET activated = FALSE WHERE id = (SELECT user_id FROM reservation_process WHERE id = ?)",id);
        assertThat(submitPhone(token,id,requestId,"+5492615551234").statusCode()).isEqualTo(401);
        assertThat(request("GET","/api/reservation-processes/"+id+"/phone-submission",null,token).statusCode()).isEqualTo(401);
    }
    @Test
    void rejectsPhoneWithoutCurrentRequestOrWithExpiredRequest() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        var noRequest=submitPhone(token,id,"not-known","1234567");
        assertThat(noRequest.statusCode()).isEqualTo(409);
        assertThat(noRequest.body()).contains("PHONE_NOT_EXPECTED");
        String requestId=requestedPhone(id);
        assertThat(submitPhone(token,id,"old-request","1234567").body()).contains("PHONE_REQUEST_CHANGED");
        jdbc.update("UPDATE reservation_process SET expires_at = ? WHERE id = ?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),id);
        var expired=submitPhone(token,id,requestId,"1234567");
        assertThat(expired.statusCode()).isEqualTo(409);
        assertThat(expired.body()).contains("PHONE_REQUEST_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM phone_submission_outbox WHERE process_id = ?",Integer.class,id)).isZero();
    }
    @Test
    void concurrentPhonePostsShareOneEventAndLease() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String requestId=requestedPhone(id);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> submitPhone(token,id,requestId,"1234567"));
            var second=executor.submit(() -> submitPhone(token,id,requestId,"1234567"));
            String eventId=submissionEvent(first.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(submissionEvent(second.get(10,java.util.concurrent.TimeUnit.SECONDS))).isEqualTo(eventId);
            var lease1=executor.submit(() -> phoneOutbox.claim(eventId));
            var lease2=executor.submit(() -> phoneOutbox.claim(eventId));
            assertThat(java.util.stream.Stream.of(lease1.get(),lease2.get()).filter(java.util.Optional::isPresent).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT attempts FROM phone_submission_outbox WHERE event_id = ?",Integer.class,eventId)).isEqualTo(1);
        }
    }
    @Test
    void expiredLeaseRecoversSamePayloadAndOldWorkerCannotOverwriteNewClaim() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String requestId=requestedPhone(id);
        String eventId=submissionEvent(submitPhone(token,id,requestId,"1234567"));
        assertThat(phoneOutbox.due()).contains(eventId);
        var first=phoneOutbox.claim(eventId).orElseThrow();
        assertThat(first.toString()).doesNotContain("1234567");
        assertThat(phoneOutbox.claim(eventId)).isEmpty();
        assertThat(phoneOutbox.due()).doesNotContain(eventId);
        jdbc.update("UPDATE phone_submission_outbox SET lease_until = ? WHERE event_id = ?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),eventId);
        assertThat(phoneOutbox.due()).contains(eventId);
        var second=phoneOutbox.claim(eventId).orElseThrow();
        assertThat(second.payload()).isEqualTo(first.payload());
        assertThat(second.key()).isEqualTo(first.key());
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        phoneOutbox.published(first); phoneOutbox.failed(first);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM phone_submission_outbox WHERE event_id = ?",String.class,eventId)).isEqualTo("SENDING");
        phoneOutbox.published(second);
        assertThat(phoneOutbox.claim(eventId)).isEmpty();
        assertThat(phoneOutbox.due()).doesNotContain(eventId);
        assertThat(submitPhone(token,id,requestId,"1234567").body()).contains("PUBLISHED");
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).body()).contains("WAITING_FOR_PHONE");
    }
    @Test
    void failedPublicationWaitsBeforeRetryingAndKeepsIdentity() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String requestId=requestedPhone(id);
        String eventId=submissionEvent(submitPhone(token,id,requestId,"1234567"));
        var delivery=phoneOutbox.claim(eventId).orElseThrow(); phoneOutbox.failed(delivery);
        assertThat(phoneOutbox.claim(eventId)).isEmpty();
        assertThat(phoneOutbox.due()).doesNotContain(eventId);
        jdbc.update("UPDATE phone_submission_outbox SET next_attempt_at = ? WHERE event_id = ?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),eventId);
        var retry=phoneOutbox.claim(eventId).orElseThrow();
        assertThat(retry.payload()).isEqualTo(delivery.payload());
        assertThat(retry.eventId()).isEqualTo(eventId);
        phoneOutbox.published(retry);
        assertThat(submitPhone(token,id,requestId,"1234567").body()).contains("PUBLISHED","publishedAt");
    }
    @ParameterizedTest
    @ValueSource(strings={"expiry","request","status"})
    void stopsQueuedPublicationIfRequestIsNoLongerUsable(String change) throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String requestId=requestedPhone(id);
        String eventId=submissionEvent(submitPhone(token,id,requestId,"1234567"));
        switch(change) {
            case "expiry" -> jdbc.update("UPDATE phone_submission_outbox SET expires_at = ? WHERE event_id = ?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),eventId);
            case "request" -> jdbc.update("UPDATE reservation_process SET phone_request_event_id = 'new-request' WHERE id = ?",id);
            default -> jdbc.update("UPDATE reservation_process SET status = 'REJECTED' WHERE id = ?",id);
        }
        assertThat(phoneOutbox.claim(eventId)).isEmpty();
        assertThat(phoneOutbox.due()).doesNotContain(eventId);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM phone_submission_outbox WHERE event_id = ?",String.class,eventId)).isEqualTo("STOPPED");
        assertThat(submitPhone(token,id,requestId,"1234567").body()).contains("STOPPED");
    }
    @Test
    void newerRequestCanReceiveDifferentPhoneWithoutReusingOldEvent() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String requestId=requestedPhone(id);
        String oldId=submissionEvent(submitPhone(token,id,requestId,"1234567"));
        var newer=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:05:00Z");
        bookingInbox.receive(newer);
        assertThat(phoneOutbox.claim(oldId)).isEmpty();
        String nextId=submissionEvent(submitPhone(token,id,newer.eventId(),"7654321"));
        assertThat(nextId).isNotEqualTo(oldId);
        assertThat(request("GET","/api/reservation-processes/"+id+"/phone-submission",null,token).body()).contains(nextId).doesNotContain(oldId);
        assertThat(phoneOutbox.claim(nextId)).isPresent();
        assertThat(phoneOutbox.claim("missing")).isEmpty();
    }

    private ar.edu.programacion2.booking.phone.BookingEvent resultEvent(String id,String type,String occurred) {
        var process=jdbc.queryForMap("SELECT * FROM reservation_process WHERE id = ?",id);
        var body=new java.util.HashMap<String,Object>();
        body.put("eventId",java.util.UUID.randomUUID().toString()); body.put("eventType",type);
        body.put("reservationProcessId",process.get("remote_process_id")); body.put("holdId",process.get("hold_id"));
        body.put("groupId","proyecto-pilar-machinea"); body.put("schemaVersion",1); body.put("occurredAt",occurred);
        body.put("requestEventId",process.get("phone_request_event_id") == null ? "unseen-request" : process.get("phone_request_event_id"));
        if(type.equals("AppointmentConfirmed") || type.equals("AppointmentCancelled")) {
            body.put("reservationId",8251); body.put("professionalId",process.get("professional_id"));
            body.put("date",process.get("appointment_date").toString()); body.put("startTime",process.get("start_time").toString());
            body.put("status",type.equals("AppointmentConfirmed") ? "CONFIRMED" : "CANCELLED");
            if(type.equals("AppointmentCancelled")) body.put("reason",null);
        } else {
            body.put("reason",type.equals("AdditionalInformationRejected") ? "INVALID_PHONE_NUMBER" : "HOLD_EXPIRED");
            if(type.equals("AdditionalInformationRejected")) {
                body.put("message","Corregir el telefono"); body.put("expiresAt","2030-01-01T11:00:00Z");
            }
        }
        return ar.edu.programacion2.booking.phone.BookingEvent.parse((String)process.get("remote_process_id"),json.writeValueAsString(body));
    }
    @Test
    void confirmedResultIsOwnedDurableAndStopsLatePhoneWorkers() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String requestId=requestedPhone(id);
        String path="/api/reservation-processes/"+id+"/result";
        assertThat(request("GET",path,null,token).statusCode()).isEqualTo(204);
        String submission=submissionEvent(submitPhone(token,id,requestId,"1234567"));
        var lease=phoneOutbox.claim(submission).orElseThrow();
        var event=resultEvent(id,"AppointmentConfirmed","2030-01-01T10:10:00Z");
        bookingInbox.receive(event); bookingInbox.receive(event); phoneOutbox.published(lease);
        assertThat(inboxState(event.eventId())).isEqualTo("APPLIED");
        var result=request("GET",path,null,token);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("CONFIRMED","8251",event.eventId());
        assertThat(result.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(request("GET",path,null,holdUser()).statusCode()).isEqualTo(404);
        assertThat(request("GET",path,null,null).statusCode()).isEqualTo(401);
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).body()).contains("CONFIRMED");
        assertThat(confirmHold(id,token).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM phone_submission_outbox WHERE event_id = ?",String.class,submission)).isEqualTo("STOPPED");
        var newHold=createHold(holdBody(),token);
        assertThat(newHold.statusCode()).isEqualTo(409); assertThat(newHold.body()).contains("PROCESS_ALREADY_PENDING");
        jdbc.update("UPDATE application_user SET activated = FALSE WHERE id = (SELECT user_id FROM reservation_process WHERE id = ?)",id);
        assertThat(request("GET",path,null,token).statusCode()).isEqualTo(401);
    }
    @ParameterizedTest
    @ValueSource(strings={"AppointmentProcessExpired","AppointmentProcessInvalid"})
    void expiredAndInvalidResultsStopProcessAndDoNotRestartConfirmation(String type) throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); requestedPhone(id);
        var event=resultEvent(id,type,"2030-01-01T10:10:00Z"); bookingInbox.receive(event);
        String status=type.equals("AppointmentProcessExpired") ? "EXPIRED" : "INVALID";
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).body()).contains(status);
        assertThat(confirmHold(id,token).statusCode()).isEqualTo(409);
        var late=resultEvent(id,"AppointmentConfirmed","2030-01-01T10:09:00Z"); bookingInbox.receive(late);
        assertThat(inboxState(late.eventId())).isEqualTo("IGNORED");
        var future=resultEvent(id,"AdditionalInformationRejected","2030-01-01T10:11:00Z"); bookingInbox.receive(future);
        assertThat(inboxState(future.eventId())).isEqualTo("IGNORED");
    }
    @Test
    void phoneRejectionAllowsExplicitCorrectionWithNewEventAndSafeOldReplay() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); String requestId=requestedPhone(id);
        String original=submissionEvent(submitPhone(token,id,requestId,"1234567"));
        var oldLease=phoneOutbox.claim(original).orElseThrow();
        var rejected=resultEvent(id,"AdditionalInformationRejected","2030-01-01T10:10:00Z"); bookingInbox.receive(rejected);
        assertThat(request("GET","/api/reservation-processes/"+id,null,token).body()).contains("WAITING_FOR_PHONE","INVALID_PHONE_NUMBER");
        var result=request("GET","/api/reservation-processes/"+id+"/result",null,token);
        assertThat(result.body()).contains("Corregir el telefono",rejected.eventId());
        assertThat(submissionEvent(submitPhone(token,id,requestId,"1234567"))).isEqualTo(original);
        String correctedBody=json.writeValueAsString(java.util.Map.of("requestEventId",requestId,"phoneNumber","7654321","rejectionEventId",rejected.eventId()));
        String correction=submissionEvent(request("POST","/api/reservation-processes/"+id+"/phone",correctedBody,token));
        assertThat(correction).isNotEqualTo(original);
        assertThat(submissionEvent(request("POST","/api/reservation-processes/"+id+"/phone",correctedBody,token))).isEqualTo(correction);
        bookingInbox.receive(rejected); phoneOutbox.published(oldLease);
        assertThat(phoneOutbox.claim(original)).isEmpty();
        assertThat(phoneOutbox.claim(correction)).isPresent();
        assertThat(request("GET","/api/reservation-processes/"+id+"/phone-submission",null,token).body()).contains(correction).doesNotContain(original);
        assertThat(request("POST","/api/reservation-processes/"+id+"/phone",correctedBody.replace(rejected.eventId(),"wrong-rejection"),token).body()).contains("PHONE_REJECTION_CHANGED");
        var older=resultEvent(id,"AdditionalInformationRejected","2030-01-01T10:09:00Z"); bookingInbox.receive(older);
        assertThat(inboxState(older.eventId())).isEqualTo("IGNORED");
    }
    @Test
    void deferredAndUncorrelatedResultsAreRecoveredWithoutAnotherKafkaDelivery() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); requestedPhone(id);
        var event=resultEvent(id,"AppointmentConfirmed","2030-01-01T10:10:00Z");
        jdbc.update("INSERT INTO booking_event_inbox(event_id,remote_process_id,event_type,payload,processing_status,received_at) VALUES(?,?,?,?,'DEFERRED',?)",
            event.eventId(),event.processId(),event.type(),event.payload().toString(),java.sql.Timestamp.from(java.time.Instant.now()));
        assertThat(bookingInbox.pending()).contains(event.eventId());
        new ar.edu.programacion2.booking.phone.PendingBookingEvents(bookingInbox).recover();
        assertThat(inboxState(event.eventId())).isEqualTo("APPLIED");
        String second=createHeldProcess(holdUser());
        var unknown=resultEvent(second,"AppointmentCancelled","2030-01-01T10:10:00Z");
        jdbc.update("UPDATE reservation_process SET remote_process_id = NULL WHERE id = ?",second);
        bookingInbox.receive(unknown); assertThat(inboxState(unknown.eventId())).isEqualTo("PENDING");
        jdbc.update("UPDATE reservation_process SET remote_process_id = ? WHERE id = ?",unknown.processId(),second);
        new ar.edu.programacion2.booking.phone.PendingBookingEvents(bookingInbox).recover();
        assertThat(inboxState(unknown.eventId())).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_process WHERE id = ?",String.class,second)).isEqualTo("CANCELLED");
    }
    @Test
    void cancellationMayArriveBeforeConfirmationAndNeverRegresses() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); requestedPhone(id);
        var cancel=resultEvent(id,"AppointmentCancelled","2030-01-01T10:11:00Z"); bookingInbox.receive(cancel);
        var confirm=resultEvent(id,"AppointmentConfirmed","2030-01-01T10:10:00Z"); bookingInbox.receive(confirm);
        assertThat(inboxState(confirm.eventId())).isEqualTo("IGNORED");
        var expired=resultEvent(id,"AppointmentProcessExpired","2030-01-01T10:12:00Z"); bookingInbox.receive(expired);
        assertThat(inboxState(expired.eventId())).isEqualTo("IGNORED");
        assertThat(request("GET","/api/reservation-processes/"+id+"/result",null,token).body()).contains("CANCELLED",cancel.eventId(),"8251");
        assertThat(confirmHold(id,token).statusCode()).isEqualTo(409);
    }
    @Test
    void confirmedReservationOnlyAdvancesToCancellationIncludingEqualTimestamp() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); requestedPhone(id);
        var confirmed=resultEvent(id,"AppointmentConfirmed","2030-01-01T10:10:00Z"); bookingInbox.receive(confirmed);
        var expired=resultEvent(id,"AppointmentProcessExpired","2030-01-01T10:12:00Z"); bookingInbox.receive(expired);
        assertThat(inboxState(expired.eventId())).isEqualTo("IGNORED");
        var cancel=resultEvent(id,"AppointmentCancelled","2030-01-01T10:10:00Z"); bookingInbox.receive(cancel);
        assertThat(inboxState(cancel.eventId())).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT reservation_id FROM reservation_process WHERE id = ?",Long.class,id)).isEqualTo(8251L);
    }
    @ParameterizedTest
    @ValueSource(strings={"groupId","holdId","professionalId","date","startTime","reservationId"})
    void mismatchedResultRollsBackWithoutChangingReservation(String field) throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); requestedPhone(id);
        var confirmed=resultEvent(id,"AppointmentConfirmed","2030-01-01T10:10:00Z"); bookingInbox.receive(confirmed);
        var next=resultEvent(id,"AppointmentCancelled","2030-01-01T10:11:00Z");
        var node=(tools.jackson.databind.node.ObjectNode)next.payload().deepCopy();
        switch(field) {
            case "professionalId", "reservationId" -> node.put(field,9999);
            case "date" -> node.put(field,"2099-12-01");
            case "startTime" -> node.put(field,"23:00");
            default -> node.put(field,"other");
        }
        var bad=ar.edu.programacion2.booking.phone.BookingEvent.parse(next.processId(),node.toString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bookingInbox.receive(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_event_inbox WHERE event_id = ?",Integer.class,next.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_process WHERE id = ?",String.class,id)).isEqualTo("CONFIRMED");
    }
    @Test
    void oldRequestResultsAreIgnoredButInvalidMismatchIsPreserved() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); requestedPhone(id);
        var rejected=resultEvent(id,"AdditionalInformationRejected","2030-01-01T10:10:00Z");
        jdbc.update("UPDATE reservation_process SET phone_request_event_id = 'new-request' WHERE id = ?",id);
        bookingInbox.receive(rejected); assertThat(inboxState(rejected.eventId())).isEqualTo("IGNORED");
        var node=(tools.jackson.databind.node.ObjectNode)rejected.payload().deepCopy();
        node.put("eventId",java.util.UUID.randomUUID().toString());node.put("eventType","AppointmentProcessInvalid");node.put("reason","REQUEST_EVENT_MISMATCH");
        var invalid=ar.edu.programacion2.booking.phone.BookingEvent.parse(rejected.processId(),node.toString());
        bookingInbox.receive(invalid); assertThat(inboxState(invalid.eventId())).isEqualTo("APPLIED");
    }
    @Test
    void rejectionBeforePhoneRequestWaitsUntilRequestCanBeCorrelated() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token);
        var rejected=resultEvent(id,"AdditionalInformationRejected","2030-01-01T10:10:00Z"); bookingInbox.receive(rejected);
        assertThat(inboxState(rejected.eventId())).isEqualTo("PENDING");
        assertThat(bookingInbox.pending()).doesNotContain(rejected.eventId());
        jdbc.update("UPDATE reservation_process SET phone_request_event_id = 'unseen-request',status = 'WAITING_FOR_PHONE' WHERE id = ?",id);
        new ar.edu.programacion2.booking.phone.PendingBookingEvents(bookingInbox).recover();
        assertThat(inboxState(rejected.eventId())).isEqualTo("APPLIED");
        var oldPhone=phoneEvent(id,java.util.UUID.randomUUID().toString(),"2030-01-01T10:01:00Z"); bookingInbox.receive(oldPhone);
        assertThat(inboxState(oldPhone.eventId())).isEqualTo("IGNORED");
    }

    @Test
    void rejectionDoesNotAcceptUnrelatedReservationFields() throws Exception {
        String token=holdUser(); String id=createHeldProcess(token); requestedPhone(id);
        var rejection=resultEvent(id,"AdditionalInformationRejected","2030-01-01T10:10:00Z");
        var node=(tools.jackson.databind.node.ObjectNode)rejection.payload().deepCopy();
        node.put("reservationId",9999);
        bookingInbox.receive(ar.edu.programacion2.booking.phone.BookingEvent.parse(rejection.processId(),node.toString()));
        assertThat(jdbc.queryForObject("SELECT reservation_id FROM reservation_process WHERE id = ?",Long.class,id)).isNull();
    }
}
