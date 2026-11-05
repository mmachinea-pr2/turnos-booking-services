package ar.edu.programacion2.booking.availability;

public class AvailabilityUnavailableException extends RuntimeException {
    public AvailabilityUnavailableException() { super("Availability source unavailable or invalid"); }
}
