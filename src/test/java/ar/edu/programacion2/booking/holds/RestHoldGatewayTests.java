package ar.edu.programacion2.booking.holds;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RestHoldGatewayTests {
    HttpServer server;
    RestHoldGateway gateway;
    String url;
    int status=201;
    boolean dropResponse;
    String response="{\"holdId\":\"hold-1\",\"reservationProcessId\":\"process-1\",\"status\":\"HELD\",\"expiresAt\":\"2026-11-05T15:00:00Z\"}";
    final AtomicInteger calls=new AtomicInteger();
    final AtomicReference<String> body=new AtomicReference<>();
    final AtomicReference<String> auth=new AtomicReference<>();
    final CreateHoldRequest request=new CreateHoldRequest(UUID.randomUUID(),15,LocalDate.of(2026,11,9),LocalTime.of(9,0));
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/appointment-holds",exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (dropResponse) {
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(status==302) exchange.getResponseHeaders().set("Location",url+"/api/appointment-holds");
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        url="http://127.0.0.1:"+server.getAddress().getPort();
        gateway=new RestHoldGateway(url,"technical-token");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void sendsTechnicalCredentialsAndServerChosenPatientAndRetainsIdentifiers() {
        var result=gateway.create(request,"owner-id");
        assertThat(result.status()).isEqualTo(ReservationProcess.Status.HELD);
        assertThat(result.holdId()).isEqualTo("hold-1");
        assertThat(result.processId()).isEqualTo("process-1");
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-11-05T15:00:00Z"));
        assertThat(auth.get()).isEqualTo("Bearer technical-token");
        assertThat(body.get()).contains("\"externalPatientId\":\"owner-id\"","\"professionalId\":15","2026-11-09")
            .doesNotContain("requestId");
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"SLOT_ALREADY_HELD","SLOT_ALREADY_RESERVED"})
    void confirmedConflictIsDefinitive(String code) {
        status=409; response="{\"code\":\""+code+"\"}";
        assertThat(gateway.create(request,"owner")).isEqualTo(HoldGateway.Outcome.rejected(code));
    }
    @ParameterizedTest @ValueSource(strings={"PROFESSIONAL_DISABLED","INVALID_SLOT","VALIDATION_ERROR"})
    void validationRejectionsAreDefinitive(String code) {
        status=400; response="{\"code\":\""+code+"\"}";
        assertThat(gateway.create(request,"owner")).isEqualTo(HoldGateway.Outcome.rejected(code));
    }
    @Test void missingProfessionalIsDefinitive() {
        status=404; response="{\"code\":\"PROFESSIONAL_NOT_FOUND\"}";
        assertThat(gateway.create(request,"owner").status()).isEqualTo(ReservationProcess.Status.REJECTED);
    }
    @ParameterizedTest @ValueSource(ints={200,202,302,400,401,403,404,409,429,500,503})
    void unexpectedResponsesAreUncertainAndNeverRetried(int value) {
        status=value;
        assertThat(gateway.create(request,"owner")).isEqualTo(HoldGateway.Outcome.uncertain());
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"not-json","null","{}","{\"code\":123}","{\"code\":\"SLOT_ALREADY_HELD\"}"})
    void unrecognizedErrorBodyIsUncertain(String value) {
        status=500; response=value;
        assertThat(gateway.create(request,"owner")).isEqualTo(HoldGateway.Outcome.uncertain());
    }
    @ParameterizedTest @ValueSource(strings={"not-json","null","{}",
        "{\"holdId\":\"\",\"reservationProcessId\":\"p\",\"status\":\"HELD\",\"expiresAt\":\"2026-11-05T15:00:00Z\"}",
        "{\"holdId\":\"h\",\"reservationProcessId\":\"\",\"status\":\"HELD\",\"expiresAt\":\"2026-11-05T15:00:00Z\"}",
        "{\"holdId\":\"h\",\"reservationProcessId\":\"p\",\"status\":\"CONFIRMED\",\"expiresAt\":\"2026-11-05T15:00:00Z\"}",
        "{\"holdId\":\"h\",\"reservationProcessId\":\"p\",\"status\":\"HELD\"}"})
    void incompleteSuccessRemainsUncertain(String value) {
        response=value;
        assertThat(gateway.create(request,"owner")).isEqualTo(HoldGateway.Outcome.uncertain());
    }
    @Test void oversizedIdentifierRemainsUncertain() {
        response=response.replace("hold-1","h".repeat(101));
        assertThat(gateway.create(request,"owner")).isEqualTo(HoldGateway.Outcome.uncertain());
    }
    @Test void missingCredentialDoesNotSendRequest() {
        gateway=new RestHoldGateway(url,"");
        assertThat(gateway.create(request,"owner")).isEqualTo(HoldGateway.Outcome.rejected("TECHNICAL_CREDENTIAL_MISSING"));
        assertThat(calls.get()).isZero();
    }
    @Test void lostResponseAfterReceivingPostIsUncertainAndNotRetried() {
        dropResponse=true;
        assertThat(gateway.create(request,"owner")).isEqualTo(HoldGateway.Outcome.uncertain());
        assertThat(calls.get()).isEqualTo(1);
        assertThat(body.get()).contains("externalPatientId");
    }
    @Test void connectionFailureRemainsUncertain() {
        server.stop(0);
        assertThat(gateway.create(request,"owner")).isEqualTo(HoldGateway.Outcome.uncertain());
    }
}
