package ar.edu.programacion2.booking.appointments;

import ar.edu.programacion2.booking.availability.AvailabilityUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RestAppointmentGatewayTests {
    HttpServer server;
    RestAppointmentGateway gateway;
    String url;
    int status = 200;
    String total = "1";
    String item = """
        {"reservationId":8251,"reservationProcessId":"process-1","externalPatientId":"user-1",
         "professionalId":15,"appointmentDate":"2030-01-01","startTime":"09:00:00","endTime":"09:30:00",
         "status":"CONFIRMED","confirmedAt":"2029-12-01T10:00:00Z","cancelledAt":null,
         "patientPhone":"private-phone","patientFirstName":"private-name"}
        """;
    String response;
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> uri = new AtomicReference<>(), auth = new AtomicReference<>();
    @BeforeEach void start() throws Exception {
        response = "["+item+"]";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            calls.incrementAndGet(); uri.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if (total != null) exchange.getResponseHeaders().set("X-Total-Count",total);
            if (status == 302) exchange.getResponseHeaders().set("Location",url+"/redirect");
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start(); url = "http://127.0.0.1:"+server.getAddress().getPort();
        gateway = new RestAppointmentGateway(url,"technical-token");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void filtersByOwnerUsesTechnicalTokenAndDoesNotExposePersonalFields() {
        var page = gateway.find("user-1",0,20);
        assertThat(page.content()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.toString()).doesNotContain("private-phone","private-name");
        assertThat(uri.get()).isEqualTo("/api/appointments?externalPatientId=user-1&page=0&size=20");
        assertThat(auth.get()).isEqualTo("Bearer technical-token");
    }
    @Test void supportsEmptyLastPageAndCancellation() {
        response="[]"; total="0";
        assertThat(gateway.find("user-1",0,20).content()).isEmpty();
        total="1"; response="["+item.replace("CONFIRMED","CANCELLED").replace("\"cancelledAt\":null","\"cancelledAt\":\"2030-01-01T10:00:00Z\"")+"]";
        assertThat(gateway.find("user-1",0,20).content().getFirst().cancelledAt()).isNotNull();
    }
    @ParameterizedTest @ValueSource(strings={"user-1","process-1","2030-01-01","09:30:00","CONFIRMED","2029-12-01T10:00:00Z"})
    void rejectsWrongOwnerOrMalformedFields(String field) {
        response=response.replace(field,field.equals("process-1") ? "" : "bad");
        assertThatThrownBy(() -> gateway.find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","1.5","9223372036854775808","\"8251\"","null"})
    void rejectsInvalidIdentifier(String value) {
        response=response.replace("8251",value);
        assertThatThrownBy(() -> gateway.find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(strings={"bad","{}","null","[]"})
    void failsClosedForMalformedOrIncompletePage(String value) {
        response=value;
        assertThatThrownBy(() -> gateway.find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(ints={201,202,302,401,403,500})
    void errorsAndRedirectsDoNotReturnEmptySuccess(int code) {
        status=code;
        assertThatThrownBy(() -> gateway.find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"-1","0","2","bad",""})
    void rejectsInconsistentCount(String count) {
        total=count;
        assertThatThrownBy(() -> gateway.find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @Test void rejectsMissingCountDuplicateProcessAndMissingFinalTimestamp() {
        total=null;
        assertThatThrownBy(() -> gateway.find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
        total="2"; response="["+item+","+item.replace("8251","8252")+"]";
        assertThatThrownBy(() -> gateway.find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
        response="["+item+","+item+"]";
        assertThatThrownBy(() -> gateway.find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
        total="1"; response="["+item.replace("\"2029-12-01T10:00:00Z\"","null")+"]";
        assertThatThrownBy(() -> gateway.find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
    }
    @Test void validatesPaginationAndCredentialBeforeNetwork() {
        assertThatThrownBy(() -> gateway.find("user-1",-1,20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.find("user-1",0,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.find("user-1",0,101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestAppointmentGateway(url,"").find("user-1",0,20)).isInstanceOf(AvailabilityUnavailableException.class);
        assertThat(calls.get()).isZero();
    }
}
