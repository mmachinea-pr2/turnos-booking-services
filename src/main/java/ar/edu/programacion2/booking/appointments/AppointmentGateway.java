package ar.edu.programacion2.booking.appointments;

import java.util.List;

public interface AppointmentGateway {
    Page find(String user, int page, int size);
    record Page(List<Appointment> content, long totalElements, int page, int size) {
        public Page { content = List.copyOf(content); }
    }
}
