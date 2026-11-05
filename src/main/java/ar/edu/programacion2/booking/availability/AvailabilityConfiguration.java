package ar.edu.programacion2.booking.availability;

import java.net.URI;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
        var uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid integration base URL");
        }
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                    throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
            .defaultStatusHandler(status -> !status.is2xxSuccessful(), (request, response) -> {
                throw new AvailabilityUnavailableException();
            }).build();
    }
}
