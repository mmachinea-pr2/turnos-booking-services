package ar.edu.programacion2.booking.appointments;

import ar.edu.programacion2.booking.integration.IntegrationHttpClient;
import ar.edu.programacion2.booking.availability.AvailabilityUnavailableException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RestAppointmentGateway implements AppointmentGateway {
    private final RestClient client;
    private final String token;
    private final JsonMapper json = JsonMapper.builder().build();
    public RestAppointmentGateway(@Value("${booking.catedra.base-url}") String baseUrl,
            @Value("${booking.catedra.token:}") String token) {
        client = IntegrationHttpClient.builder(baseUrl).build();
        this.token = token;
    }
    @Override public Page find(String user, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        if (token.isBlank()) throw new AvailabilityUnavailableException();
        try {
            var response = client.get().uri(builder -> builder.path("/api/appointments")
                .queryParam("externalPatientId",user).queryParam("page",page).queryParam("size",size)
                .build())
                .headers(headers -> headers.setBearerAuth(token)).retrieve().toEntity(String.class);
            if (response.getStatusCode().value() != 200 || response.getBody() == null) throw invalid();
            long total = Long.parseLong(Objects.requireNonNull(response.getHeaders().getFirst("X-Total-Count")));
            var body = json.readTree(response.getBody());
            if (!body.isArray() || body.size() > size || total < 0
                    || (!body.isEmpty() && total < (long)page * size + body.size())) throw invalid();
            List<Appointment> appointments = new ArrayList<>();
            Set<Long> ids = new HashSet<>();
            Set<String> processes = new HashSet<>();
            for (var item : body) {
                var appointment = parse(item,user);
                if (!ids.add(appointment.reservationId()) || !processes.add(appointment.reservationProcessId())) throw invalid();
                appointments.add(appointment);
            }
            if (body.size() != Math.min(size,Math.max(0,total - (long)page * size))) throw invalid();
            return new Page(appointments,total,page,size);
        } catch (RuntimeException exception) {
            // Never expose the upstream body, token or another patient's data.
            throw new AvailabilityUnavailableException();
        }
    }
    private Appointment parse(JsonNode node, String user) {
        String owner = text(node,"externalPatientId",100);
        if (!user.equals(owner)) throw invalid();
        String status = text(node,"status",20);
        if (!Set.of("PHONE_PENDING","CONFIRMED","CANCELLED","FAILED").contains(status)) throw invalid();
        LocalDate date = LocalDate.parse(text(node,"appointmentDate",10));
        LocalTime start = LocalTime.parse(text(node,"startTime",18));
        LocalTime end = LocalTime.parse(text(node,"endTime",18));
        if (!end.isAfter(start) || start.getNano() != 0 || end.getNano() != 0) throw invalid();
        Instant confirmed = instant(node,"confirmedAt");
        Instant cancelled = instant(node,"cancelledAt");
        if (("CONFIRMED".equals(status) && confirmed == null) || ("CANCELLED".equals(status) && cancelled == null)) throw invalid();
        return new Appointment(positive(node,"reservationId"),text(node,"reservationProcessId",100),owner,
            positive(node,"professionalId"),date,start,end,status,confirmed,cancelled);
    }
    private long positive(JsonNode node, String name) {
        var value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw invalid();
        return value.longValue();
    }
    private String text(JsonNode node, String name, int max) {
        var value = node.path(name);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > max) throw invalid();
        return value.asString();
    }
    private Instant instant(JsonNode node, String name) {
        return node.path(name).isMissingNode() || node.path(name).isNull() ? null : Instant.parse(text(node,name,40));
    }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid appointment response"); }
}
