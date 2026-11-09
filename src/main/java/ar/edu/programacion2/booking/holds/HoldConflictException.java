package ar.edu.programacion2.booking.holds;

public class HoldConflictException extends RuntimeException {
    public HoldConflictException(String code) { super(code); }
}
