package ar.edu.programacion2.booking.users;

import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[a-zA-Z0-9_.@-]+") String login,
        @NotNull @Size(min = 4, max = 100) String password,
        @Size(max = 50) String firstName,
        @Size(max = 50) String lastName,
        @NotBlank @Email @Size(min = 5, max = 254) String email,
        @Size(max = 256) @Pattern(regexp = "https?://[^\\s]+") String imageUrl,
        @Size(min = 2, max = 10) @Pattern(regexp = "[a-zA-Z-]+") String langKey) {
    @Override public String toString() { return "RegisterRequest[redacted]"; }
}
