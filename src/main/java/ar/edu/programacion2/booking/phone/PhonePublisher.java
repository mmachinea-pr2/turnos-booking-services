package ar.edu.programacion2.booking.phone;

@FunctionalInterface
public interface PhonePublisher {
    void publish(PhoneSubmissionOutbox.Delivery delivery);
}
