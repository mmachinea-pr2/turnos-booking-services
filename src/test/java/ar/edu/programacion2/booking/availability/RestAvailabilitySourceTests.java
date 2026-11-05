package ar.edu.programacion2.booking.availability;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RestAvailabilitySourceTests {
    HttpServer server;
    AvailabilitySource source;
    String url;
    int status = 200;
    String response = "[]";
    String catalogStatus = "{\"initialized\":true,\"appliedVersion\":1}";
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> query = new AtomicReference<>();
    final LocalDate date = LocalDate.of(2026,11,9);
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            String body = exchange.getRequestURI().getPath().endsWith("/status") ? catalogStatus : response;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if (status == 302) exchange.getResponseHeaders().set("Location",url + "/redirect");
            exchange.sendResponseHeaders(status,bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        url = "http://127.0.0.1:"+server.getAddress().getPort();
        source = new AvailabilityConfiguration().availabilitySource(url,url,"technical-token");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void schedulesPropagateUserTokenAndAcceptCatalogExtraFields() {
        response = "[{\"id\":21,\"professionalId\":15,\"dayOfWeek\":\"MONDAY\",\"startTime\":\"09:00:00\",\"endTime\":\"12:00:00\",\"slotDurationMinutes\":30}]";
        assertThat(source.schedules(15,"user-token")).hasSize(1);
        assertThat(authorization.get()).isEqualTo("Bearer user-token");
    }
    @Test void occupanciesUseOnlyTechnicalTokenAndExactDateRange() {
        response = "{\"professionalId\":15,\"from\":\"2026-11-09\",\"to\":\"2026-11-09\",\"occupancies\":[{\"date\":\"2026-11-09\",\"startTime\":\"09:00:00\",\"status\":\"CONFIRMED\",\"expiresAt\":null}]}";
        assertThat(source.occupancies(15,date)).hasSize(1);
        assertThat(authorization.get()).isEqualTo("Bearer technical-token");
        assertThat(query.get()).isEqualTo("professionalId=15&from=2026-11-09&to=2026-11-09");
    }
    @ParameterizedTest @ValueSource(ints={302,401,403,404,429,500,503})
    void remoteErrorsAreSanitized(int code) {
        status=code;
        assertThatThrownBy(() -> source.schedules(15,"user-token")).isInstanceOf(AvailabilityUnavailableException.class)
            .hasMessageNotContaining("token");
        assertThatThrownBy(() -> source.occupancies(15,date)).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(strings={"null","{}","{\"initialized\":false}","not-json"})
    void uninitializedOrInvalidCatalogStatusFails(String body) {
        catalogStatus=body;
        assertThatThrownBy(() -> source.schedules(15,"user-token")).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(strings={"null","not-json","{}"})
    void invalidSchedulesBodyFails(String body) {
        response=body;
        assertThatThrownBy(() -> source.schedules(15,"user-token")).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(strings={"null","not-json","{}",
        "{\"professionalId\":16,\"from\":\"2026-11-09\",\"to\":\"2026-11-09\",\"occupancies\":[]}",
        "{\"professionalId\":15,\"from\":\"2026-11-08\",\"to\":\"2026-11-09\",\"occupancies\":[]}",
        "{\"professionalId\":15,\"from\":\"2026-11-09\",\"to\":\"2026-11-10\",\"occupancies\":[]}",
        "{\"professionalId\":15,\"from\":\"2026-11-09\",\"to\":\"2026-11-09\"}"})
    void mismatchedOccupanciesFailClosed(String body) {
        response=body;
        assertThatThrownBy(() -> source.occupancies(15,date)).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @Test void emptyOccupanciesAreValid() {
        response="{\"professionalId\":15,\"from\":\"2026-11-09\",\"to\":\"2026-11-09\",\"occupancies\":[]}";
        assertThat(source.occupancies(15,date)).isEmpty();
    }
    @Test void missingCredentialFailsBeforeCallingRemote() {
        var unconfigured=new AvailabilityConfiguration().availabilitySource(url,url,"");
        assertThatThrownBy(() -> unconfigured.occupancies(15,date)).isInstanceOf(AvailabilityUnavailableException.class);
        assertThat(authorization.get()).isNull();
    }
    @Test void connectionFailureIsSanitized() {
        server.stop(0);
        assertThatThrownBy(() -> source.schedules(15,"user-token")).isInstanceOf(AvailabilityUnavailableException.class);
        assertThatThrownBy(() -> source.occupancies(15,date)).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(strings={"file:///tmp/test","https://user:pass@example.test","http://example.test?a=b","http://example.test#part","relative"})
    void invalidBaseUrlIsRejected(String base) {
        assertThatThrownBy(() -> new AvailabilityConfiguration().availabilitySource(base,url,"token"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void zoneConfigurationUsesRequestedZone() {
        assertThat(new AvailabilityConfiguration().availabilityClock("America/Argentina/Mendoza").getZone().getId())
            .isEqualTo("America/Argentina/Mendoza");
    }
}
