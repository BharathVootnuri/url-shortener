package com.bharath.projects.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation-only description of the error body (RFC 7807 {@code application/problem+json}).
 * The application never instantiates this: errors are produced as Spring {@code ProblemDetail}s by the
 * global exception handler. It exists so the OpenAPI spec shows the real, flat JSON shape including the
 * {@code code} and {@code traceId} extensions (a plain {@code ProblemDetail} would be documented with a
 * misleading nested {@code properties} map).
 */
@Schema(name = "ApiError", description = "Error response (RFC 7807 problem details with two extensions).")
public record ApiError(
        @Schema(description = "URI reference identifying the problem type.", example = "urn:urlshortener:problem:link-not-found")
        String type,

        @Schema(description = "Short, human-readable summary of the HTTP status.", example = "Not Found")
        String title,

        @Schema(description = "HTTP status code.", example = "404")
        int status,

        @Schema(description = "Human-readable explanation. Never contains internal details.",
                example = "No link exists for code 'abc1234'.")
        String detail,

        @Schema(description = "Request path the error relates to.", example = "/api/v1/short-urls/abc1234")
        String instance,

        @Schema(description = "Stable machine-readable error code. Clients should branch on this, not on 'detail'. "
                + "Application codes: INVALID_URL, INVALID_EXPIRY, INVALID_RANGE, INVALID_PARAMETER, "
                + "MALFORMED_REQUEST, LINK_NOT_FOUND, LINK_EXPIRED, RATE_LIMITED, PAYLOAD_TOO_LARGE, "
                + "DATABASE_UNAVAILABLE, CODE_ALLOCATION_FAILED, DATABASE_ERROR, INTERNAL_ERROR. "
                + "Errors raised by the framework itself (e.g. wrong method or media type) use the HTTP status "
                + "name, such as METHOD_NOT_ALLOWED.",
                example = "LINK_NOT_FOUND")
        String code,

        @Schema(description = "Correlation id of the request; also returned in the X-Correlation-Id header. "
                + "Quote it when reporting a problem.",
                example = "6f1c2d9e4b7a4c0f")
        String traceId) {
}
