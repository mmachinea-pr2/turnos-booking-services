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
        String code = errorCode(exception);
        if (code == null) return Outcome.uncertain();
        int status = exception.getStatusCode().value();
        boolean rejected = (status == 409 && CONFLICTS.contains(code))
            || (status == 400 && INVALID.contains(code))
            || (status == 404 && "PROFESSIONAL_NOT_FOUND".equals(code));
        return rejected ? Outcome.rejected(code) : Outcome.uncertain();
    }
    private String errorCode(RestClientResponseException exception) {
        try {
            var code = json.readTree(exception.getResponseBodyAsString()).get("code");
            return code != null && code.isString() ? code.asText() : null;
        } catch (tools.jackson.core.JacksonException ignored) {
            return null;
        }
    }
    @Override public Outcome confirm(ReservationProcess process, ConfirmHoldRequest request, String userId) {
        if (technicalToken.isBlank()) return Outcome.confirmationUncertain();
        try {
            var response = client.post().uri("/api/appointment-holds/{holdId}/confirm",process.holdId())
                .headers(h -> h.setBearerAuth(technicalToken)).contentType(MediaType.APPLICATION_JSON)
                .body(new RemoteConfirmationRequest(userId,request.patientFirstName(),request.patientLastName()))
                .retrieve().toEntity(RemoteConfirmation.class);
            var confirmed = response.getBody();
            if (response.getStatusCode().value() != 202 || !validConfirmation(process,confirmed)) {
                return Outcome.confirmationUncertain();
            }
            return new Outcome(ReservationProcess.Status.WAITING_FOR_PHONE,process.holdId(),
                confirmed.reservationProcessId(),confirmed.expiresAt(),null);
        } catch (RestClientResponseException exception) {
            return confirmationRejectionOrUncertain(exception);
        } catch (RestClientException exception) {
            return Outcome.confirmationUncertain();
        }
    }
    private boolean validConfirmation(ReservationProcess process, RemoteConfirmation response) {
        return response != null && process.reservationProcessId().equals(response.reservationProcessId())
            && "WAITING_FOR_PHONE".equals(response.status()) && response.expiresAt() != null;
    }
    private Outcome confirmationRejectionOrUncertain(RestClientResponseException exception) {
        String code = errorCode(exception);
        if (code == null) return Outcome.confirmationUncertain();
        int status = exception.getStatusCode().value();
        if (status == 404 && "HOLD_EXPIRED".equals(code)) {
            return new Outcome(ReservationProcess.Status.EXPIRED,null,null,null,code);
        }
        boolean rejected = (status == 400 && Set.of("INVALID_SLOT","EXTERNAL_PATIENT_MISMATCH","VALIDATION_ERROR").contains(code))
            || (status == 404 && "PROFESSIONAL_NOT_FOUND".equals(code))
            || (status == 409 && "SLOT_ALREADY_RESERVED".equals(code));
        // HOLD_NOT_FOUND and ALREADY_CONFIRMED do not prove that the reservation was not created.
        return rejected ? Outcome.rejected(code) : Outcome.confirmationUncertain();
    }
    public record RemoteConfirmationRequest(String externalPatientId, String patientFirstName, String patientLastName) {
        @Override public String toString() { return "RemoteConfirmationRequest[redacted]"; }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteConfirmation(String reservationProcessId, String status, Instant expiresAt) {}
    public record RemoteRequest(long professionalId, java.time.LocalDate date, java.time.LocalTime startTime,
                                String externalPatientId) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteHold(String holdId, String reservationProcessId, String status, Instant expiresAt) {}
}
