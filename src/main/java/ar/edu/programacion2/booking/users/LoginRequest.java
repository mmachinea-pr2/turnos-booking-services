package ar.edu.programacion2.booking.users;

import jakarta.validation.constraints.*;

public record LoginRequest(@NotBlank @Size(max = 254) String username,
        @NotNull @Size(min = 4, max = 100) String password, Boolean rememberMe) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
