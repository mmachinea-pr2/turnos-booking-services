package ar.edu.programacion2.booking.cancellation;

import jakarta.validation.constraints.Size;

public record CancelRequest(@Size(max=500) String reason) {
    @Override public String toString() { return "CancelRequest[redacted]"; }
}
