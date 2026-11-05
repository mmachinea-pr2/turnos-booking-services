package ar.edu.programacion2.booking.availability;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = AvailabilityController.class)
public class AvailabilityErrors {
    @ExceptionHandler(AvailabilityUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable() {
        return ResponseEntity.status(503).body(new ErrorResponse("AVAILABILITY_UNAVAILABLE"));
    }
    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalid() {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_AVAILABILITY_REQUEST"));
    }
    public record ErrorResponse(String code) {}
}
