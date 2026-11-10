package ar.edu.programacion2.booking.phone;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitPhoneRequest(@NotBlank @Size(max = 100) String requestEventId,
        @NotBlank @Size(max = 100) String phoneNumber, @Size(max = 100) String rejectionEventId) {
    public SubmitPhoneRequest(String requestEventId, String phoneNumber) { this(requestEventId,phoneNumber,null); }
    public String responseRound() { return rejectionEventId == null ? "INITIAL" : rejectionEventId; }
    public String normalizedPhone() {
        String phone = phoneNumber.replaceAll("[ ().-]", "");
        if (!phone.matches("\\+?[0-9]{7,15}")) throw new IllegalArgumentException("Invalid phone number");
        return phone;
    }
    @Override public String toString() { return "SubmitPhoneRequest[redacted]"; }
}
