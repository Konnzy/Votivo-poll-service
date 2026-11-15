package ua.votivo.poll.exception;

import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(PollNotFoundException.class)
    ProblemDetail notFound(PollNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidPollStateException.class)
    ProblemDetail conflict(InvalidPollStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Poll changed concurrently; reload and retry");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Operation not permitted");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthorized() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList());
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
