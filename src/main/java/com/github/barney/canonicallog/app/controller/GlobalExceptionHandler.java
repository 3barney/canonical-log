package com.github.barney.canonicallog.app.controller;

import com.github.barney.canonicallog.app.models.dto.RepaymentDto.ErrorResponse;
import com.github.barney.canonicallog.lib.context.CanonicalLogContext;
import com.github.barney.canonicallog.lib.context.ObservabilityContext;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> "%s: %s".formatted(fe.getField(), fe.getDefaultMessage()))
            .collect(Collectors.joining(", "));

        recordError("validation", ex, details);

        return ResponseEntity.badRequest().body(new ErrorResponse("FAILED", "VALIDATION_ERROR", details, correlationId()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String details = ex.getConstraintViolations().stream()
            .map(v -> "%s: %s".formatted(v.getPropertyPath(), v.getMessage()))
            .collect(Collectors.joining(", "));

        recordError("validation", ex, details);

        return ResponseEntity.badRequest().body(new ErrorResponse("FAILED", "CONSTRAINT_VIOLATION", details, correlationId()));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleTimeout(ResourceAccessException ex) {
        recordError("outbound_call", ex, "External service unreachable: %s".formatted(ex.getMessage()));

        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(new ErrorResponse(
            "FAILED", "UPSTREAM_TIMEOUT", "External service unavailable", correlationId())
        );
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamError(HttpServerErrorException ex) {
        recordError("outbound_call", ex, "Upstream returned " + ex.getStatusCode());

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(
            "FAILED", "UPSTREAM_ERROR", "External service returned error: " + ex.getStatusCode(), correlationId())
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadArgument(IllegalArgumentException ex) {
        recordError("business_logic", ex, ex.getMessage());

        return ResponseEntity.badRequest().body(new ErrorResponse("FAILED", "BAD_REQUEST", ex.getMessage(), correlationId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        recordError("unexpected", ex, ex.getMessage());

        return ResponseEntity.internalServerError().body(new ErrorResponse(
            "FAILED", "INTERNAL_ERROR", "An unexpected error occurred", correlationId())
        );
    }

    private void recordError(String phase, Exception ex, String message) {
        CanonicalLogContext logContext = ObservabilityContext.logContext();
        if (logContext == null) return;

        logContext.addEvent(new CanonicalLogContext.ErrorLogEvent(
            Instant.now(), phase, ex.getClass().getSimpleName(), message, stackSnippet(ex)
        ));
    }

    private String correlationId() {
        CanonicalLogContext ctx = ObservabilityContext.logContext();
        return ctx != null ? ctx.getCorrelationId() : null;
    }

    private String stackSnippet(Exception exception) {
        if (exception.getStackTrace().length == 0) return "";
        StringBuilder stringBuilder = new StringBuilder();
        int limit = Math.min(5, exception.getStackTrace().length);
        for (int i = 0; i < limit; i++) {
            if (i > 0) stringBuilder.append(" → ");
            stringBuilder.append(exception.getStackTrace()[i].toString());
        }
        return stringBuilder.toString();
    }
}
