package ar.edu.programacion2.booking.users;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = AccountController.class)
public class AccountErrors {
    @ExceptionHandler(UserService.DuplicateUserException.class)
    ResponseEntity<ErrorResponse> duplicate() { return error(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS"); }
    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorResponse> invalidCredentials() { return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidRequest() { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"); }
    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new ErrorResponse(code));
    }
    public record ErrorResponse(String code) {}
}
