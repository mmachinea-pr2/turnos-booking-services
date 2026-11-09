package ar.edu.programacion2.booking.holds;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmHoldRequest(@NotBlank @Size(min = 2, max = 100) String patientFirstName,
                                 @NotBlank @Size(min = 2, max = 100) String patientLastName) {
    public ConfirmHoldRequest {
        patientFirstName = patientFirstName == null ? null : patientFirstName.strip();
        patientLastName = patientLastName == null ? null : patientLastName.strip();
    }
    @Override public String toString() { return "ConfirmHoldRequest[redacted]"; }
}
