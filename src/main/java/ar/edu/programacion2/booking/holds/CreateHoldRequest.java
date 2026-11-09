package ar.edu.programacion2.booking.holds;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record CreateHoldRequest(@NotNull UUID requestId, @Positive long professionalId,
        @NotNull LocalDate date, @NotNull LocalTime startTime) {}
