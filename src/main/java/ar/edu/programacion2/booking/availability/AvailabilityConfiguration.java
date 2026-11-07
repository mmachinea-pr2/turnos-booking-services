package ar.edu.programacion2.booking.availability;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class AvailabilityConfiguration {
    @Bean Clock availabilityClock(@Value("${booking.availability.zone:America/Argentina/Mendoza}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
    @Bean AvailabilitySource availabilitySource(
            @Value("${booking.catalog.base-url:http://localhost:8080}") String catalog,
            @Value("${booking.catedra.base-url:http://172.17.201.1:8090}") String catedra,
            @Value("${booking.catedra.token:}") String token) {
        return new RestAvailabilitySource(client(catalog), client(catedra), token);
    }
    private RestClient client(String baseUrl) {
        return ar.edu.programacion2.booking.integration.IntegrationHttpClient.builder(baseUrl)
            .defaultStatusHandler(status -> !status.is2xxSuccessful(), (request, response) -> {
                throw new AvailabilityUnavailableException();
            }).build();
    }
}
