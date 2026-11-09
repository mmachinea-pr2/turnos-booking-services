package ar.edu.programacion2.booking.holds;

import ar.edu.programacion2.booking.integration.IntegrationHttpClient;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RestHoldGateway implements HoldGateway {
    private static final Set<String> CONFLICTS = Set.of("SLOT_ALREADY_RESERVED","SLOT_ALREADY_HELD");
    private static final Set<String> INVALID = Set.of("PROFESSIONAL_DISABLED","INVALID_SLOT","VALIDATION_ERROR");
    private final RestClient client;
    private final String technicalToken;
    private final JsonMapper json = JsonMapper.builder().build();
    public RestHoldGateway(@Value("${booking.catedra.base-url:http://172.17.201.1:8090}") String baseUrl,
            @Value("${booking.catedra.token:}") String technicalToken) {
        this.client = IntegrationHttpClient.builder(baseUrl).build();
        this.technicalToken = technicalToken;
    }
    @Override public Outcome create(CreateHoldRequest request, String userId) {
        if (technicalToken.isBlank()) return Outcome.rejected("TECHNICAL_CREDENTIAL_MISSING");
        try {
            var response = client.post().uri("/api/appointment-holds")
                .headers(h -> h.setBearerAuth(technicalToken)).contentType(MediaType.APPLICATION_JSON)
                .body(new RemoteRequest(request.professionalId(),request.date(),request.startTime(),userId))
                .retrieve().toEntity(RemoteHold.class);
            var hold = response.getBody();
            if (response.getStatusCode().value() != 201 || !valid(hold)) return Outcome.uncertain();
            return new Outcome(ReservationProcess.Status.HELD,hold.holdId(),hold.reservationProcessId(),hold.expiresAt(),null);
        } catch (RestClientResponseException exception) {
            return rejectionOrUncertain(exception);
        } catch (RestClientException exception) {
            return Outcome.uncertain();
        }
    }
    private boolean valid(RemoteHold hold) {
        return hold != null && identifier(hold.holdId()) && identifier(hold.reservationProcessId())
            && "HELD".equals(hold.status()) && hold.expiresAt() != null;
    }
    private boolean identifier(String value) { return value != null && !value.isBlank() && value.length() <= 100; }
    private Outcome rejectionOrUncertain(RestClientResponseException exception) {
        try {
            var codeNode = json.readTree(exception.getResponseBodyAsString()).get("code");
            if (codeNode == null || !codeNode.isString()) return Outcome.uncertain();
            String code = codeNode.asText();
            int status = exception.getStatusCode().value();
            boolean rejected = (status == 409 && CONFLICTS.contains(code))
                || (status == 400 && INVALID.contains(code))
                || (status == 404 && "PROFESSIONAL_NOT_FOUND".equals(code));
            return rejected ? Outcome.rejected(code) : Outcome.uncertain();
        } catch (tools.jackson.core.JacksonException exceptionIgnored) {
            return Outcome.uncertain();
        }
    }
    public record RemoteRequest(long professionalId, java.time.LocalDate date, java.time.LocalTime startTime,
                                String externalPatientId) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteHold(String holdId, String reservationProcessId, String status, Instant expiresAt) {}
}
