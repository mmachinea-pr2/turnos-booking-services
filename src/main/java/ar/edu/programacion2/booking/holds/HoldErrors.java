package ar.edu.programacion2.booking.holds;

import ar.edu.programacion2.booking.availability.AvailabilityUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {HoldController.class, ConfirmHoldController.class, ar.edu.programacion2.booking.phone.PhoneSubmissionController.class})
public class HoldErrors {
    @ExceptionHandler(HoldConflictException.class)
    ResponseEntity<ErrorResponse> conflict(HoldConflictException exception) { return error(409,exception.getMessage()); }
    @ExceptionHandler(ProcessNotFoundException.class)
    ResponseEntity<ErrorResponse> missing() { return error(404,"PROCESS_NOT_FOUND"); }
    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorResponse> credentials() { return error(401,"INVALID_CREDENTIALS"); }
    @ExceptionHandler({AvailabilityUnavailableException.class, DataAccessException.class})
    ResponseEntity<ErrorResponse> unavailable() { return error(503,"HOLD_SERVICE_UNAVAILABLE"); }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalid() { return error(400,"INVALID_HOLD_REQUEST"); }
    private ResponseEntity<ErrorResponse> error(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(code));
    }
    public record ErrorResponse(String code) {}
}
