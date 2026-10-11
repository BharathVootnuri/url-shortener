package com.bharath.projects.urlshortener.exception;

import com.bharath.projects.urlshortener.filter.CorrelationIdFilter;
import com.bharath.projects.urlshortener.util.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * One place that turns every failure into the documented error contract:
 * RFC 7807 {@code application/problem+json} with a stable machine-readable {@code code} and the request's
 * {@code traceId}. Internal details (SQL, stack traces, class names) are logged, never returned.
 *
 * <p>Failures on the public redirect path ({@code GET /{code}}) are content-negotiated: browsers (which send
 * {@code Accept: text/html}) get a tiny static HTML page, API clients get problem+json.
 * Extending {@link ResponseEntityExceptionHandler} brings standard Spring MVC errors (405, 415, 404 for
 * unmapped paths, ...) into the same format.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String PROBLEM_TYPE_PREFIX = "urn:urlshortener:problem:";

    private final RateLimiter rateLimiter;

    public GlobalExceptionHandler(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    // ---------------------------------------------------------------- domain errors

    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<Object> invalidUrl(InvalidUrlException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_URL", "Invalid URL", ex.getMessage(), null);
    }

    @ExceptionHandler(InvalidExpiryException.class)
    public ResponseEntity<Object> invalidExpiry(InvalidExpiryException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_EXPIRY", "Invalid expiration", ex.getMessage(), null);
    }

    @ExceptionHandler(InvalidRangeException.class)
    public ResponseEntity<Object> invalidRange(InvalidRangeException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_RANGE", "Invalid time range", ex.getMessage(), null);
    }

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<Object> notFound(LinkNotFoundException ex, HttpServletRequest request) {
        rateLimiter.recordMiss(request.getRemoteAddr());
        return respond(request, HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", "Link not found", ex.getMessage(), null);
    }

    @ExceptionHandler(LinkExpiredException.class)
    public ResponseEntity<Object> expired(LinkExpiredException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.GONE, "LINK_EXPIRED", "Link expired", ex.getMessage(), null);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Object> rateLimited(RateLimitExceededException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests",
                "Too many requests. Please retry later.", retryAfter(ex.getRetryAfterSeconds()));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<Object> payloadTooLarge(PayloadTooLargeException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Request too large",
                ex.getMessage(), null);
    }

    @ExceptionHandler(CodeGenerationException.class)
    public ResponseEntity<Object> codeGeneration(CodeGenerationException ex, HttpServletRequest request) {
        log.error("Short code allocation failed: {}", ex.getMessage());
        return respond(request, HttpStatus.SERVICE_UNAVAILABLE, "CODE_ALLOCATION_FAILED", "Temporarily unavailable",
                "Could not create the link right now. Please try again.", retryAfter(1));
    }

    // ---------------------------------------------------------------- database errors

    /** Connection/availability problems: the client can reasonably retry, so 503 with Retry-After. */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
            TransactionException.class})
    public ResponseEntity<Object> databaseUnavailable(Exception ex, HttpServletRequest request) {
        log.error("Database unavailable", ex);
        return respond(request, HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Service unavailable",
                "The service is temporarily unavailable. Please try again shortly.", retryAfter(5));
    }

    /** Any other database failure (constraint or query bug): not retryable, so 500. Details stay in the logs. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Object> databaseError(DataAccessException ex, HttpServletRequest request) {
        log.error("Database error", ex);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR", "Internal error",
                "An internal error occurred.", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An internal error occurred.", null);
    }

    // ---------------------------------------------------------------- standard Spring MVC errors

    /**
     * Adds {@code code} and {@code traceId} to every problem Spring produces itself (405, 415, 404, ...).
     * Spring passes a {@code null} body for these and builds the ProblemDetail inside the super call,
     * so the response is enriched afterwards rather than before.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                HttpStatus status = HttpStatus.resolve(statusCode.value());
                problem.setProperty("code", status != null ? status.name() : "ERROR");
            }
            problem.setProperty("traceId", MDC.get(CorrelationIdFilter.MDC_KEY));
        }
        return response;
    }

    /**
     * Bad request bodies. A bad {@code expiresAt} or {@code url} value reports the same code as the
     * equivalent validation failure (INVALID_EXPIRY / INVALID_URL); anything else is MALFORMED_REQUEST.
     * Jackson's message is never echoed.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        String field = offendingField(ex);
        String code = "MALFORMED_REQUEST";
        String title = "Malformed request";
        String detail = "Request body is missing or is not valid JSON.";
        if ("expiresAt".equals(field)) {
            code = "INVALID_EXPIRY";
            title = "Invalid expiration";
            detail = "expiresAt must be an ISO-8601 date-time, for example 2030-01-01T00:00:00Z.";
        } else if ("url".equals(field)) {
            code = "INVALID_URL";
            title = "Invalid URL";
            detail = "url must be a string.";
        } else if (field != null) {
            detail = "Request body has an invalid value for '" + field + "'.";
        }
        return handleExceptionInternal(ex, badRequest(code, title, detail), headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Bad query parameters such as {@code granularity=WEEK} or {@code from=yesterday}. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        ProblemDetail problem = badRequest("INVALID_PARAMETER", "Invalid parameter",
                "Invalid value for parameter '" + name + "'.");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<Object> respond(HttpServletRequest request, HttpStatus status, String code,
                                           String title, String detail, HttpHeaders headers) {
        HttpHeaders responseHeaders = headers != null ? headers : new HttpHeaders();
        if (prefersHtml(request)) {
            responseHeaders.setContentType(MediaType.TEXT_HTML);
            responseHeaders.setCacheControl("no-store");
            return ResponseEntity.status(status).headers(responseHeaders).body(htmlPage(title, publicMessage(status)));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        problem.setProperty("traceId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(status).headers(responseHeaders).body(problem);
    }

    private static ProblemDetail badRequest(String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }

    private static HttpHeaders retryAfter(long seconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        return headers;
    }

    /** Only the public redirect path (anything outside /api/) serves HTML, and only to clients that ask for it. */
    private static boolean prefersHtml(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return !path.startsWith("/api/") && accept != null && accept.contains("text/html");
    }

    /** Fixed text per status: nothing from the request is ever put into the HTML page. */
    private static String publicMessage(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "We couldn't find a link for that address. Check it and try again.";
            case GONE -> "This link has expired and no longer redirects.";
            case TOO_MANY_REQUESTS -> "Too many requests. Please wait a moment and try again.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable. Please try again shortly.";
            default -> "Something went wrong.";
        };
    }

    private static String htmlPage(String title, String message) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + title + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:32rem;margin:15vh auto;padding:0 1rem;"
                + "color:#222}h1{font-size:1.5rem}</style></head>"
                + "<body><h1>" + title + "</h1><p>" + message + "</p></body></html>";
    }

    /** Name of the JSON property that failed to deserialize, if Jackson reports one. */
    private static String offendingField(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof MismatchedInputException mismatch) {
                List<JacksonException.Reference> path = mismatch.getPath();
                if (!path.isEmpty()) {
                    return path.get(path.size() - 1).getPropertyName();
                }
            }
        }
        return null;
    }
}
