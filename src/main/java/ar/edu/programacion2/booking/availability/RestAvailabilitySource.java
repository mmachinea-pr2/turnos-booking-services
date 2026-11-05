package ar.edu.programacion2.booking.availability;

import java.time.LocalDate;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class RestAvailabilitySource implements AvailabilitySource {
    private final RestClient catalog;
    private final RestClient catedra;
    private final String technicalToken;
    public RestAvailabilitySource(RestClient catalog, RestClient catedra, String technicalToken) {
        this.catalog = catalog;
        this.catedra = catedra;
        this.technicalToken = technicalToken;
    }

    @Override public List<Schedule> schedules(long professionalId, String userToken) {
        try {
            var status = catalog.get().uri("/api/catalog/synchronization/status")
                .headers(h -> h.setBearerAuth(userToken)).retrieve().body(CatalogStatus.class);
            if (status == null || !Boolean.TRUE.equals(status.initialized())) throw new AvailabilityUnavailableException();
            var result = catalog.get().uri("/api/catalog/professionals/{id}/schedules", professionalId)
                .headers(h -> h.setBearerAuth(userToken)).retrieve()
                .body(new ParameterizedTypeReference<List<Schedule>>() {});
            if (result == null) throw new AvailabilityUnavailableException();
            return result;
        } catch (RestClientException exception) {
            throw new AvailabilityUnavailableException();
        }
    }

    @Override public List<Occupancy> occupancies(long professionalId, LocalDate date) {
        if (technicalToken.isBlank()) throw new AvailabilityUnavailableException();
        try {
            var result = catedra.get().uri(builder -> builder.path("/api/appointment-occupancies")
                    .queryParam("professionalId", professionalId).queryParam("from", date).queryParam("to", date).build())
                .headers(h -> h.setBearerAuth(technicalToken)).retrieve().body(Occupancies.class);
            if (result == null || result.professionalId() == null || result.professionalId() != professionalId
                    || !date.equals(result.from()) || !date.equals(result.to()) || result.occupancies() == null) {
                throw new AvailabilityUnavailableException();
            }
            return result.occupancies();
        } catch (RestClientException exception) {
            throw new AvailabilityUnavailableException();
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogStatus(Boolean initialized) {}
    public record Occupancies(Long professionalId, LocalDate from, LocalDate to, List<Occupancy> occupancies) {}
}
