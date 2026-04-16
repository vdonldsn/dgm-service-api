package com.dgm.api.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Validation errors (400) ───────────────────────────────────────────────
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            fieldErrors.put(field, error.getDefaultMessage());
        });

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                400,
                "Validation failed",
                request.getRequestURI(),
                fieldErrors
        );
        return ResponseEntity.badRequest().body(body);
    }

    // ── Not found (404) ──────────────────────────────────────────────────────
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            RecordNotFoundException ex,
            HttpServletRequest request) {

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                404,
                ex.getMessage(),
                request.getRequestURI(),
                null
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // ── Generic runtime errors (500) ─────────────────────────────────────────
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(
            RuntimeException ex,
            HttpServletRequest request) {

        // Log full stack trace server-side, return sanitized message to client
        log.error("Unhandled exception on {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                500,
                "An unexpected error occurred. Please try again.",
                request.getRequestURI(),
                null
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ── Error response record ────────────────────────────────────────────────
    public record ErrorResponse(
            Instant timestamp,
            int status,
            String message,
            String path,
            Map<String, String> fieldErrors
    ) {}

    // ── Custom exception ─────────────────────────────────────────────────────
    public static class RecordNotFoundException extends RuntimeException {
        public RecordNotFoundException(String message) {
            super(message);
        }
    }
}
