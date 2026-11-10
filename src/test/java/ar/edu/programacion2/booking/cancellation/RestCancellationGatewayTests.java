package ar.edu.programacion2.booking.cancellation;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RestCancellationGatewayTests {
    HttpServer server;
    RestCancellationGateway gateway;
    String url;
    int status=200;
    boolean drop;
    String response="{\"reservationId\":8251,\"reservationProcessId\":\"process-1\",\"status\":\"CANCELLED\",\"cancelledAt\":\"2030-01-01T10:10:00Z\"}";
    final AtomicInteger calls=new AtomicInteger();
    final AtomicReference<String> body=new AtomicReference<>(),auth=new AtomicReference<>(),path=new AtomicReference<>();
    Cancellation cancellation=new Cancellation("local","process-1",8251L,"CONFIRMED","REQUESTING","Cambio",null,null,null);
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if(drop) { exchange.close(); return; }
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(status==302) exchange.getResponseHeaders().set("Location",url+"/redirect");
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start(); url="http://127.0.0.1:"+server.getAddress().getPort();
        gateway=new RestCancellationGateway(url,"technical-token");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void usesStoredIdentityTechnicalTokenAndReason() {
        var result=gateway.cancel(cancellation);
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.cancelledAt()).isNotNull();
        assertThat(path.get()).isEqualTo("/api/appointments/process-1/cancel");
        assertThat(auth.get()).isEqualTo("Bearer technical-token");
        assertThat(body.get()).isEqualTo("{\"reason\":\"Cambio\"}");
        assertThat(cancellation.toString()).doesNotContain("Cambio");
        assertThat(new CancelRequest("Cambio").toString()).doesNotContain("Cambio");
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","bad-json","{\"status\":\"CANCELLED\"}"})
    void malformedRepliesAreUncertain(String payload) {
        response=payload; assertThat(gateway.cancel(cancellation).status()).isEqualTo("UNCERTAIN");
    }
    @ParameterizedTest @ValueSource(strings={"process-1","8251","CANCELLED","2030-01-01T10:10:00Z"})
    void invalidCorrelationOrTimestampIsUncertain(String field) {
        response=response.replace(field,field.equals("8251") ? "99" : "wrong");
        assertThat(gateway.cancel(cancellation).status()).isEqualTo("UNCERTAIN");
    }
    @ParameterizedTest @ValueSource(ints={201,202,302,400,401,403,404,409,500,503})
    void unexpectedStatusDoesNotConfirmOrRedirect(int code) {
        status=code; assertThat(gateway.cancel(cancellation).status()).isEqualTo("UNCERTAIN");
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"RESERVATION_NOT_FOUND","RESERVATION_OWNER_MISMATCH","STUDENT_INTEGRATION_NOT_FOUND","RESERVATION_NOT_CANCELLABLE"})
    void recognizedErrorsArePreserved(String code) {
        status=code.equals("RESERVATION_NOT_FOUND") ? 404 : code.equals("RESERVATION_NOT_CANCELLABLE") ? 409 : 403;
        response="{\"code\":\""+code+"\"}";
        var result=gateway.cancel(cancellation);
        assertThat(result.status()).isEqualTo("REJECTED"); assertThat(result.errorCode()).isEqualTo(code);
    }
    @Test void lostReplyDoesNotRetryAndMissingTokenDoesNotSend() {
        drop=true; assertThat(gateway.cancel(cancellation).status()).isEqualTo("UNCERTAIN");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(new RestCancellationGateway(url,"").cancel(cancellation).errorCode()).isEqualTo("TECHNICAL_CREDENTIAL_MISSING");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void malformedErrorIsUncertain() {
        status=409; response="bad-json"; assertThat(gateway.cancel(cancellation).status()).isEqualTo("UNCERTAIN");
    }
}
