package ar.edu.programacion2.booking.cancellation;

import ar.edu.programacion2.booking.integration.IntegrationHttpClient;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RestCancellationGateway implements CancellationGateway {
    private final RestClient client;
    private final String token;
    private final JsonMapper json = JsonMapper.builder().build();
    public RestCancellationGateway(@Value("${booking.catedra.base-url}") String baseUrl,
            @Value("${booking.catedra.token:}") String token) {
        this.client = IntegrationHttpClient.builder(baseUrl).build();
        this.token = token;
    }
    @Override public Outcome cancel(Cancellation cancellation) {
        if (token.isBlank()) return new Outcome("REJECTED",null,"TECHNICAL_CREDENTIAL_MISSING");
        try {
            var response = client.post().uri("/api/appointments/{process}/cancel",cancellation.reservationProcessId())
                .headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(new CancelRequest(cancellation.reason())).retrieve().toEntity(RemoteCancellation.class);
            var body = response.getBody();
            if (response.getStatusCode().value() != 200 || body == null || !"CANCELLED".equals(body.status())
                    || !cancellation.reservationProcessId().equals(body.reservationProcessId())
                    || !cancellation.reservationId().equals(body.reservationId()) || body.cancelledAt() == null) {
                return Outcome.uncertain();
            }
            return new Outcome("CANCELLED",body.cancelledAt(),null);
        } catch (RestClientResponseException exception) {
            return rejection(exception);
        } catch (RestClientException exception) {
            return Outcome.uncertain();
        }
    }
    private Outcome rejection(RestClientResponseException exception) {
        try {
            var code = json.readTree(exception.getResponseBodyAsString()).path("code").asString();
            int status = exception.getStatusCode().value();
            boolean known = (status == 404 && "RESERVATION_NOT_FOUND".equals(code))
                || (status == 403 && ("RESERVATION_OWNER_MISMATCH".equals(code) || "STUDENT_INTEGRATION_NOT_FOUND".equals(code)))
                || (status == 409 && "RESERVATION_NOT_CANCELLABLE".equals(code));
            return known ? new Outcome("REJECTED",null,code) : Outcome.uncertain();
        } catch (RuntimeException exceptionIgnored) {
            return Outcome.uncertain();
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public record RemoteCancellation(Long reservationId, String reservationProcessId, String status, Instant cancelledAt) {}
}
