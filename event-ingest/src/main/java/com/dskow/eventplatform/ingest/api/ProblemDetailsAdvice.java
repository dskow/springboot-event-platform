package com.dskow.eventplatform.ingest.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps validation and parse failures to <a href="https://www.rfc-editor.org/rfc/rfc9457.html">
 * RFC 9457</a> Problem Details responses ({@code application/problem+json}).
 *
 * Spring's default error envelope is replaced so every 4xx/5xx response on this
 * service follows a single, machine-readable shape: {@code type}, {@code title},
 * {@code status}, {@code detail}, {@code instance}, plus an {@code errors} array
 * for field-level validation failures so clients can surface the bad fields
 * directly without parsing prose.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice {

    private static final URI VALIDATION_TYPE =
        URI.create("https://www.rfc-editor.org/rfc/rfc9457.html#name-validation-error");
    private static final URI MALFORMED_TYPE =
        URI.create("https://www.rfc-editor.org/rfc/rfc9457.html#name-malformed-request");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of(
                "field", fe.getField(),
                "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
            .toList();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "One or more fields failed validation");
        pd.setType(VALIDATION_TYPE);
        pd.setTitle("Validation failed");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformed(HttpMessageNotReadableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Request body could not be parsed as JSON");
        pd.setType(MALFORMED_TYPE);
        pd.setTitle("Malformed request");
        return pd;
    }
}
